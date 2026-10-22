import { expect, test } from 'vitest';

import { GeneralTokenizer } from '../tokenizer';

test('tokenizer', () => {
  {
    const tokens = new GeneralTokenizer().tokenize('hello  world,\n Cloud Notes');

    expect(tokens).toEqual([
      { term: 'hello', start: 0, end: 5 },
      { term: 'world', start: 7, end: 12 },
      { term: 'cloud-notes', start: 15, end: 21 },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('你好世界，阿芬');

    expect(tokens).toEqual([
      {
        end: 2,
        start: 0,
        term: '你好',
      },
      {
        end: 3,
        start: 1,
        term: '好世',
      },
      {
        end: 4,
        start: 2,
        term: '世界',
      },
      {
        end: 7,
        start: 5,
        term: '阿芬',
      },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('1阿2芬');

    expect(tokens).toEqual([
      { term: '1', start: 0, end: 1 },
      { term: '阿', start: 1, end: 2 },
      { term: '2', start: 2, end: 3 },
      { term: '芬', start: 3, end: 4 },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('안녕하세요 세계');

    expect(tokens).toEqual([
      {
        end: 2,
        start: 0,
        term: '안녕',
      },
      {
        end: 3,
        start: 1,
        term: '녕하',
      },
      {
        end: 4,
        start: 2,
        term: '하세',
      },
      {
        end: 5,
        start: 3,
        term: '세요',
      },
      {
        end: 8,
        start: 6,
        term: '세계',
      },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('ハローワールド');

    expect(tokens).toEqual([
      { term: 'ハロ', start: 0, end: 2 },
      { term: 'ロー', start: 1, end: 3 },
      { term: 'ーワ', start: 2, end: 4 },
      { term: 'ワー', start: 3, end: 5 },
      { term: 'ール', start: 4, end: 6 },
      { term: 'ルド', start: 5, end: 7 },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('はろーわーるど');

    expect(tokens).toEqual([
      { term: 'はろ', start: 0, end: 2 },
      { term: 'ろー', start: 1, end: 3 },
      { term: 'ーわ', start: 2, end: 4 },
      { term: 'わー', start: 3, end: 5 },
      { term: 'ーる', start: 4, end: 6 },
      { term: 'るど', start: 5, end: 7 },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('👋1️⃣🚪👋🏿');

    expect(tokens).toEqual([
      { term: '👋', start: 0, end: 2 },
      { term: '1️⃣', start: 2, end: 5 },
      { term: '🚪', start: 5, end: 7 },
      { term: '👋🏿', start: 7, end: 11 },
    ]);
  }

  {
    const tokens = new GeneralTokenizer().tokenize('1️');

    expect(tokens).toEqual([{ term: '1️', start: 0, end: 2 }]);
  }
});
