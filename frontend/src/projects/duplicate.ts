/** The server owns collision-safe default naming; omit an untouched suggestion. */
export function duplicateNameInput(sourceName: string, enteredName: string): { name?: string } {
  return enteredName === `${sourceName} (Kopie)` ? {} : { name: enteredName }
}
