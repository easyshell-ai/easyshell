import dayjs from 'dayjs';

/**
 * Generic CSV export utility
 * @param headers - Column header labels
 * @param rows - 2D array of cell values
 * @param filename - Output filename (without extension and date suffix)
 */
export function exportCSV(
  headers: string[],
  rows: (string | number | null | undefined)[][],
  filename: string,
): void {
  const BOM = '\uFEFF';
  const csv =
    BOM +
    [headers, ...rows]
      .map((r) =>
        r.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','),
      )
      .join('\n');

  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${filename}-${dayjs().format('YYYYMMDD-HHmmss')}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
