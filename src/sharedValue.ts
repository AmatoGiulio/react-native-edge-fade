// Structural typing for any Reanimated SharedValue<T> — kept here so the
// soft peer dependency on `react-native-reanimated` stays optional. Any object
// matching this shape (including a user-defined animated value) is accepted by
// `AnimatedEdgeFadeView`.

export type SharedValueLike<T> = {
  readonly value: T;
  readonly addListener: (
    listenerID: number,
    listener: (newValue: T) => void
  ) => void;
};

export function isSharedValueLike(x: unknown): x is SharedValueLike<unknown> {
  return (
    typeof x === 'object' &&
    x !== null &&
    'value' in x &&
    typeof (x as { addListener?: unknown }).addListener === 'function'
  );
}
