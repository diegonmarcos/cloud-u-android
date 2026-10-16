export type RollupFn =
  | 'count'
  | 'count_values'
  | 'count_unique'
  | 'sum'
  | 'avg'
  | 'min'
  | 'max'
  | 'percent_empty'
  | 'percent_not_empty'
  | 'percent_checked';

export const ROLLUP_FNS: { fn: RollupFn; label: string }[] = [
  { fn: 'count', label: 'Count all' },
  { fn: 'count_values', label: 'Count values' },
  { fn: 'count_unique', label: 'Count unique values' },
  { fn: 'sum', label: 'Sum' },
  { fn: 'avg', label: 'Average' },
  { fn: 'min', label: 'Min' },
  { fn: 'max', label: 'Max' },
  { fn: 'percent_empty', label: 'Percent empty' },
  { fn: 'percent_not_empty', label: 'Percent not empty' },
  { fn: 'percent_checked', label: 'Percent checked' },
];

const isEmptyValue = (v: unknown) =>
  v == null ||
  v === '' ||
  (Array.isArray(v) && v.length === 0) ||
  (typeof v === 'number' && Number.isNaN(v));

const numbers = (values: unknown[]) =>
  values.filter((v): v is number => typeof v === 'number' && !Number.isNaN(v));

/**
 * The rollup of the values of one property across the rows a relation points to.
 * `values` has one entry per related row (empty cells included), so `count` is the number of
 * related rows and the percent functions have the right denominator. An empty relation gives
 * 0 for the counts and sums, null where no number can be given (avg, min, max, percentages).
 */
export function computeRollup(fn: RollupFn, values: unknown[]): number | null {
  const total = values.length;
  switch (fn) {
    case 'count':
      return total;
    case 'count_values':
      return values.filter(v => !isEmptyValue(v)).length;
    case 'count_unique':
      return new Set(
        values.filter(v => !isEmptyValue(v)).map(v => JSON.stringify(v))
      ).size;
    case 'sum':
      return numbers(values).reduce((a, b) => a + b, 0);
    case 'avg': {
      const n = numbers(values);
      return n.length ? n.reduce((a, b) => a + b, 0) / n.length : null;
    }
    case 'min': {
      const n = numbers(values);
      return n.length ? Math.min(...n) : null;
    }
    case 'max': {
      const n = numbers(values);
      return n.length ? Math.max(...n) : null;
    }
    case 'percent_empty':
      return total ? (values.filter(isEmptyValue).length / total) * 100 : null;
    case 'percent_not_empty':
      return total
        ? (values.filter(v => !isEmptyValue(v)).length / total) * 100
        : null;
    case 'percent_checked':
      return total ? (values.filter(v => v === true).length / total) * 100 : null;
  }
}
