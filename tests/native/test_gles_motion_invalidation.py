#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeGlesMotionInvalidator.kt"
MANAGER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeViewManager.kt"
VIEW = ROOT / "android/src/main/java/com/edgefade/EdgeFadeView.kt"

helper = HELPER.read_text()
manager = MANAGER.read_text()
view = VIEW.read_text()

required_helper_tokens = [
    "Choreographer.FrameCallback",
    "ViewTreeObserver.OnScrollChangedListener",
    "MotionEvent.ACTION_MOVE",
    "MotionEvent.ACTION_UP",
    "MOTION_GRACE_NS = 250_000_000L",
    "POST_TOUCH_GRACE_NS = 750_000_000L",
    "fun onTouchEvent(view: EdgeFadeView, actionMasked: Int)",
    "view.invalidate()",
    "Build.VERSION.SDK_INT >= Build.VERSION_CODES.S",
    "Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU",
    "view.progressiveBlurActive",
    "view.blurRadius > 0f",
    "removeFrameCallback",
    "OnAttachStateChangeListener",
]
for token in required_helper_tokens:
    assert token in helper, f"missing GLES motion invalidation contract token: {token}"

assert "EdgeFadeGlesMotionInvalidator.register(view)" in manager
assert "EdgeFadeGlesMotionInvalidator.unregister(view)" in manager

assert "override fun dispatchTouchEvent(event: MotionEvent): Boolean" in view
assert "EdgeFadeGlesMotionInvalidator.onTouchEvent(this, event.actionMasked)" in view
assert "return super.dispatchTouchEvent(event)" in view

# The bridge must be activity-bounded rather than a permanent redraw loop.
assert "frameTimeNanos > activeUntilNanos" in helper
assert "System.nanoTime() + graceNanos" in helper
assert "ACTION_UP" in helper and "POST_TOUCH_GRACE_NS" in helper

# API 33+ owns motion through the RuntimeShader/HWUI path and must not be forced
# through the API 31-32 redraw bridge.
assert "Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU" in helper

print("GLES motion invalidation contract: PASS")
