/**
 * Leaderboard: every player's single best eligible quiz, ranked by points.
 * Data: GET /api/leaderboard (the eligibility rules live on the server).
 */

import { DIFFICULTY_LABELS, LEADERBOARD_SIZE, TIMER_MODES } from '../../core/config.js';
import { emptyState, loadingState } from '../components.js';
import { formatDate, h } from '../dom.js';

const MEDALS = { 1: '🥇', 2: '🥈', 3: '🥉' };
const TIMER_LABELS = { [TIMER_MODES.QUESTION]: 'per question', [TIMER_MODES.SESSION]: 'whole quiz', [TIMER_MODES.OFF]: 'untimed' };

export function renderLeaderboardScreen(root, ctx) {
  const screen = h('section', { class: 'screen screen-leaderboard' });
  root.append(screen);
  let alive = true;

  const header = (minQuestions) =>
    h(
      'header',
      { class: 'screen-header' },
      h('h1', {}, 'Leaderboard'),
      h(
        'p',
        { class: 'lede' },
        `Each player's best quiz, ranked by points. Counts finished quizzes of ${minQuestions ?? 5}+ questions; ` +
          'retries and quizzes ended early are left out.',
      ),
    );

  async function load() {
    screen.replaceChildren(header(), loadingState('Loading the leaderboard…'));
    let board;
    try {
      board = await ctx.api.leaderboard(LEADERBOARD_SIZE);
    } catch (error) {
      if (!alive) return;
      screen.replaceChildren(
        header(),
        emptyState({
          icon: '⚠️',
          title: "Couldn't load the leaderboard",
          message: error.message,
          action: h('button', { type: 'button', class: 'btn btn-primary', onClick: () => load() }, 'Try again'),
        }),
      );
      return;
    }
    if (!alive) return;

    if (board.entries.length === 0) {
      screen.replaceChildren(
        header(board.minQuestions),
        emptyState({
          icon: '🏆',
          title: 'No scores yet',
          message: 'Be the first on the board: finish a quiz of at least five questions.',
          action: h('button', { type: 'button', class: 'btn btn-primary', onClick: () => ctx.navigate('setup') }, 'Start a quiz'),
        }),
      );
      return;
    }

    const me = ctx.player?.name?.toLowerCase();
    const rows = board.entries.map((entry) => {
      const isMe = entry.name.toLowerCase() === me;
      return h(
        'tr',
        { class: isMe ? 'is-me' : '' },
        h('td', { class: 'rank' }, MEDALS[entry.rank] ? h('span', { attrs: { 'aria-label': `Rank ${entry.rank}` } }, MEDALS[entry.rank]) : String(entry.rank)),
        h('td', {}, entry.name, isMe ? h('span', { class: 'you-badge' }, 'You') : null, h('span', { class: 'cell-sub' }, `${entry.attempts} eligible quiz${entry.attempts === 1 ? '' : 'zes'}`)),
        h('td', { class: 'num' }, h('strong', {}, String(entry.points))),
        h('td', { class: 'num' }, `${entry.percentage}%`),
        h(
          'td',
          {},
          `${entry.questionCount} questions`,
          h('span', { class: 'cell-sub' }, `${DIFFICULTY_LABELS[entry.difficulty]} · ${TIMER_LABELS[entry.timerMode]}`),
        ),
        h('td', {}, formatDate(entry.finishedAt)),
      );
    });

    // Outside the top list? Still tell the player where they stand.
    const inTable = board.you && board.entries.some((e) => e.rank === board.you.rank);
    let yourRank = null;
    if (board.you && !inTable) {
      yourRank = h(
        'p',
        { class: 'your-rank' },
        h('strong', {}, `You're #${board.you.rank} of ${board.players}`),
        ` — ${board.you.name}, best ${board.you.points} points (${board.you.percentage}%). Keep climbing!`,
      );
    } else if (ctx.player && !board.you) {
      yourRank = h('p', { class: 'your-rank' }, `Finish a quiz of ${board.minQuestions}+ questions to get ranked, ${ctx.player.name}.`);
    }

    screen.replaceChildren(
      header(board.minQuestions),
      ...(yourRank ? [yourRank] : []),
      h(
        'section',
        { class: 'card' },
        h(
          'div',
          { class: 'table-scroll', attrs: { tabindex: '0', role: 'region', 'aria-label': 'Leaderboard table' } },
          h(
            'table',
            { class: 'data-table leaderboard-table' },
            h(
              'thead',
              {},
              h(
                'tr',
                {},
                ['Rank', 'Player', 'Points', 'Score', 'Quiz', 'Date'].map((label, i) =>
                  h('th', { scope: 'col', class: i === 2 || i === 3 ? 'num' : '' }, label),
                ),
              ),
            ),
            h('tbody', {}, rows),
          ),
        ),
      ),
    );
  }

  load();
  return () => {
    alive = false;
  };
}
