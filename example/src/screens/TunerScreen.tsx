/**
 * Tuner — the shared FadePanel presented in the `/panel` form-sheet route (see
 * the `panel` screen options in `app/_layout.tsx`). Drives the same fade store
 * the gallery and detail read.
 */

import { FadePanel } from '@/components/FadePanel';

export function TunerScreen() {
  return <FadePanel />;
}
