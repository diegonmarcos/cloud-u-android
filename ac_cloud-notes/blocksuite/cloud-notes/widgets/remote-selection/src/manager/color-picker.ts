class RandomPicker<T> {
  private _copyArray: T[];

  private readonly _originalArray: T[];

  constructor(array: T[]) {
    this._originalArray = [...array];
    this._copyArray = [...array];
  }

  private randomIndex(max: number): number {
    return Math.floor(Math.random() * max);
  }

  pick(): T {
    if (this._copyArray.length === 0) {
      this._copyArray = [...this._originalArray];
    }

    const index = this.randomIndex(this._copyArray.length);
    const item = this._copyArray[index];
    this._copyArray.splice(index, 1);
    return item;
  }
}

export const multiPlayersColor = new RandomPicker([
  'var(--cloud-notes-multi-players-purple)',
  'var(--cloud-notes-multi-players-magenta)',
  'var(--cloud-notes-multi-players-red)',
  'var(--cloud-notes-multi-players-orange)',
  'var(--cloud-notes-multi-players-green)',
  'var(--cloud-notes-multi-players-blue)',
  'var(--cloud-notes-multi-players-brown)',
  'var(--cloud-notes-multi-players-grey)',
]);
