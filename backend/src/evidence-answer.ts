// Binary answers must belong to this state version's declared choices. Agreement
// between witnesses does not make an out-of-schema answer valid.
export function binaryAnswerAllowed(schema: unknown, value: unknown): boolean {
  if (
    typeof value !== "string" || !value.trim() || value.length > 64 ||
    !schema || typeof schema !== "object" || Array.isArray(schema)
  ) return false;
  const choices = (schema as Record<string, unknown>).enum;
  return Array.isArray(choices) && choices.includes(value);
}
