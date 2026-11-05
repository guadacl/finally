import { matchesSearch } from './search-box.component';

describe('matchesSearch', () => {
  it('matches the ticker or the company name, case-insensitively', () => {
    expect(matchesSearch('enph', 'ENPH', 'Enphase Energy, Inc.')).toBe(true);
    expect(matchesSearch('Enphase', 'ENPH', 'Enphase Energy, Inc.')).toBe(true);
    expect(matchesSearch('energy', 'ENPH', 'Enphase Energy, Inc.')).toBe(true);
    expect(matchesSearch('apple', 'ENPH', 'Enphase Energy, Inc.')).toBe(false);
  });

  it('needs every word to match, in any order', () => {
    expect(matchesSearch('  energy   enp ', 'ENPH', 'Enphase Energy, Inc.')).toBe(true);
    expect(matchesSearch('energy apple', 'ENPH', 'Enphase Energy, Inc.')).toBe(false);
  });

  it('matches everything when empty, and only the ticker when the name is unknown', () => {
    expect(matchesSearch('', 'AAPL')).toBe(true);
    expect(matchesSearch('   ', 'AAPL', null)).toBe(true);
    expect(matchesSearch('aap', 'AAPL', null)).toBe(true);
    expect(matchesSearch('apple', 'AAPL', null)).toBe(false);
  });
});
