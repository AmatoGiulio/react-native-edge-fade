#!/usr/bin/env python3
"""
Estimate the vertical progressive-blur profile from a reference video.

Method:
- use a sharp baseline frame;
- track the spread of a persistent vertical image edge;
- subtract the baseline edge spread in quadrature;
- median several settled frames;
- enforce monotonicity with PAVA;
- fit presence(u) ~= u^p while solving ramp start/depth.

Dependencies: python3, numpy, opencv-python.

Example:
  python3 scripts/analyze-progressive-blur-reference.py \
    "/path/to/reference.mp4" \
    --edge-x 324 \
    --baseline 0.45 \
    --settled 0.85 0.95 1.05 1.15
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import cv2
import numpy as np


def read_gray(video: str, t: float) -> np.ndarray:
    cap = cv2.VideoCapture(video)
    cap.set(cv2.CAP_PROP_POS_MSEC, t * 1000.0)
    ok, frame = cap.read()
    cap.release()
    if not ok:
        raise RuntimeError(f"Could not read {video!r} at {t:.3f}s")
    return cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY).astype(np.float32)


def edge_sigma(
    image: np.ndarray,
    edge_x: int,
    y: int,
    vertical_radius: int = 5,
    horizontal_radius: int = 30,
) -> float:
    strip = np.mean(
        image[
            y - vertical_radius : y + vertical_radius + 1,
            edge_x - horizontal_radius : edge_x + horizontal_radius + 1,
        ],
        axis=0,
    )

    # Suppress local image texture while preserving the step edge.
    strip = cv2.GaussianBlur(strip.reshape(1, -1), (0, 0), 0.45).reshape(-1)
    gradient = np.abs(np.diff(strip))

    xs = np.arange(
        edge_x - horizontal_radius + 0.5,
        edge_x + horizontal_radius + 0.5,
    )
    support = np.abs(xs - edge_x) <= 16
    gradient = gradient[support]
    xs = xs[support]

    background = np.percentile(gradient, 25)
    weights = np.maximum(gradient - background, 0.0)
    peak = float(weights.max())
    if peak <= 1e-6:
        return float("nan")

    # Keep the dominant edge plus its blur tails.
    weights = np.where(weights >= 0.05 * peak, weights, 0.0)
    total = float(weights.sum())
    if total <= 1e-6:
        return float("nan")

    mean = float((weights * xs).sum() / total)
    variance = float((weights * (xs - mean) ** 2).sum() / total)
    return math.sqrt(max(variance, 0.0))


def pava(values: np.ndarray) -> np.ndarray:
    """Monotonic-increasing isotonic regression."""
    blocks: list[list[float]] = []

    for index, value in enumerate(values):
        if not np.isfinite(value):
            value = blocks[-1][2] if blocks else 0.0

        blocks.append([float(index), float(index), float(value), 1.0])

        while len(blocks) >= 2 and blocks[-2][2] > blocks[-1][2]:
            right = blocks.pop()
            left = blocks.pop()
            count = left[3] + right[3]
            mean = (left[2] * left[3] + right[2] * right[3]) / count
            blocks.append([left[0], right[1], mean, count])

    result = np.zeros(len(values), dtype=np.float64)
    for first, last, mean, _ in blocks:
        result[int(first) : int(last) + 1] = mean
    return result


def fit_power_curve(
    ys: np.ndarray,
    presence: np.ndarray,
    fit_min_y: float,
    fit_max_y: float,
) -> dict[str, float]:
    mask = (ys >= fit_min_y) & (ys <= fit_max_y)
    y = ys[mask].astype(np.float64)
    target = presence[mask].astype(np.float64)

    best = {
        "rmse": float("inf"),
        "y0": 0.0,
        "depth": 0.0,
        "power": 0.0,
    }

    # Deterministic grid search: avoids a scipy dependency.
    for y0 in np.arange(fit_min_y - 40, fit_min_y + 61, 2.0):
        for depth in np.arange(100.0, 221.0, 2.0):
            u = np.clip((y - y0) / depth, 0.0, 1.0)
            for power in np.arange(1.0, 3.01, 0.02):
                prediction = u**power
                rmse = float(np.sqrt(np.mean((prediction - target) ** 2)))
                if rmse < best["rmse"]:
                    best = {
                        "rmse": rmse,
                        "y0": float(y0),
                        "depth": float(depth),
                        "power": float(power),
                    }

    return best


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video")
    parser.add_argument("--output", default="benchmark-results/reference-blur-profile.json")
    parser.add_argument("--edge-x", type=int, default=324)
    parser.add_argument("--baseline", type=float, default=0.45)
    parser.add_argument(
        "--settled",
        type=float,
        nargs="+",
        default=[0.85, 0.95, 1.05, 1.15],
    )
    parser.add_argument("--y-min", type=int, default=360)
    parser.add_argument("--y-max", type=int, default=560)
    parser.add_argument("--y-step", type=int, default=5)
    args = parser.parse_args()

    baseline = read_gray(args.video, args.baseline)
    ys = np.arange(args.y_min, args.y_max + 1, args.y_step)

    baseline_sigma = np.array(
        [edge_sigma(baseline, args.edge_x, int(y)) for y in ys],
        dtype=np.float64,
    )

    settled_profiles = []
    for t in args.settled:
        frame = read_gray(args.video, t)
        sigma = np.array(
            [edge_sigma(frame, args.edge_x, int(y)) for y in ys],
            dtype=np.float64,
        )
        effective = np.sqrt(np.maximum(sigma * sigma - baseline_sigma * baseline_sigma, 0.0))
        settled_profiles.append(effective)

    raw = np.nanmedian(np.stack(settled_profiles), axis=0)

    smoothed = raw.copy()
    for index in range(2, len(smoothed) - 2):
        smoothed[index] = np.nanmedian(raw[index - 2 : index + 3])

    monotonic = pava(smoothed)

    lower = monotonic[ys >= args.y_max - 25]
    plateau_sigma = float(np.percentile(lower, 75))
    presence = np.clip(monotonic / max(plateau_sigma, 1e-6), 0.0, 1.0)

    fit = fit_power_curve(
        ys,
        presence,
        fit_min_y=max(args.y_min, 380),
        fit_max_y=min(args.y_max, 550),
    )

    stops_count = 13
    alpha_stops = [
        round(1.0 - (i / (stops_count - 1)) ** fit["power"], 4)
        for i in range(stops_count)
    ]

    result = {
        "video": args.video,
        "baselineSeconds": args.baseline,
        "settledSeconds": args.settled,
        "edgeX": args.edge_x,
        "y": ys.tolist(),
        "edgeSigma": monotonic.tolist(),
        "presence": presence.tolist(),
        "plateauSigma": plateau_sigma,
        "powerFit": fit,
        "alphaStops13": alpha_stops,
        "notes": {
            "curveMeaning": "presence(u) ~= u^power",
            "stopsMeaning": "EdgeFade custom stops are alpha; native presence is 1-alpha",
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")

    print(f"Reference ramp start: {fit['y0']:.1f}px")
    print(f"Reference ramp depth: {fit['depth']:.1f}px")
    print(f"Reference presence exponent: {fit['power']:.2f}")
    print(f"Fit RMSE: {fit['rmse']:.4f}")
    print(f"13 alpha stops: {alpha_stops}")
    print(f"Report: {output}")


if __name__ == "__main__":
    main()
