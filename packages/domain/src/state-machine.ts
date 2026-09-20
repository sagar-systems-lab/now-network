export type TransitionTable<S extends string, E extends string> = Readonly<
  Record<S, Readonly<Partial<Record<E, S>>>>
>;

export class InvalidTransitionError extends Error {
  readonly code = "INVALID_TRANSITION";

  constructor(
    readonly machine: string,
    readonly state: string,
    readonly event: string,
  ) {
    super(`${machine} cannot apply ${event} from ${state}`);
    this.name = "InvalidTransitionError";
  }
}

export class TransitionGuardError extends Error {
  readonly code = "TRANSITION_GUARD_FAILED";

  constructor(
    readonly machine: string,
    readonly state: string,
    readonly event: string,
    readonly reason: string,
  ) {
    super(`${machine} rejected ${event} from ${state}: ${reason}`);
    this.name = "TransitionGuardError";
  }
}

export function nextState<S extends string, E extends string>(
  machine: string,
  table: TransitionTable<S, E>,
  current: S,
  event: E,
): S {
  const next = table[current][event];

  if (next === undefined) {
    throw new InvalidTransitionError(machine, current, event);
  }

  return next;
}

export function requireGuard(
  condition: boolean,
  machine: string,
  state: string,
  event: string,
  reason: string,
): void {
  if (!condition) {
    throw new TransitionGuardError(machine, state, event, reason);
  }
}
