// Bound fixture HTTP lifetimes, including unread response bodies during cleanup.
export async function fetch(input, init = {}) {
  try {
    return await globalThis.fetch(input, { ...init, signal: init.signal ?? AbortSignal.timeout(15_000) });
  } catch (error) {
    throw new Error(`Fixture HTTP failed: ${String(input)} (${error?.name}: ${error?.message})`, { cause: error });
  }
}
