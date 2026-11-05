import { matchesSearch, normalizeSearchText } from './table-search.component';

describe('table search', () => {
  it('matches everything on an empty query', () => {
    expect(matchesSearch('', 'AAPL', 'Apple Inc.')).toBe(true);
    expect(matchesSearch('   ', 'AAPL', null)).toBe(true);
  });

  it('matches the ticker or the company name, case-insensitively and on partial words', () => {
    expect(matchesSearch('aap', 'AAPL', 'Apple Inc.')).toBe(true);
    expect(matchesSearch('APPLE', 'AAPL', 'Apple Inc.')).toBe(true);
    expect(matchesSearch('micro', 'MSFT', 'Microsoft Corporation')).toBe(true);
    expect(matchesSearch('tesla', 'AAPL', 'Apple Inc.')).toBe(false);
  });

  it('requires every word, in any order, across ticker and name', () => {
    expect(matchesSearch('chase jpm', 'JPM', 'JPMorgan Chase & Co.')).toBe(true);
    expect(matchesSearch('chase bank', 'JPM', 'JPMorgan Chase & Co.')).toBe(false);
  });

  it('ignores accents and punctuation', () => {
    expect(normalizeSearchText('  Nestlé-S.A. ')).toBe('nestle s.a.');
    expect(matchesSearch('coca cola', 'KO', 'The Coca-Cola Company')).toBe(true);
    expect(matchesSearch('brk.b', 'BRK.B', null)).toBe(true);
  });

  it('still matches the ticker when the name is unknown', () => {
    expect(matchesSearch('nf', 'NFLX', null)).toBe(true);
    expect(matchesSearch('netflix', 'NFLX', undefined)).toBe(false);
  });
});
