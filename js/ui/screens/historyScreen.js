/**
 * History screen: the current player's lifetime stats, topic mastery and past
 * attempts (each can be reopened for a full review). Data: GET /api/me/history.
 */

import { DIFFICULTY_LABELS, TIMER_MODES } from '../../core/config.js';
import { accuracyBars, emptyState, loadingState, statTile } from '../components.js';
import { confirmDialog } from '../dialog.js';
import { formatClock, formatDate, formatDuration, h } from '../dom.js';

export function renderHistoryScreen(root, ctx) {
  const { api } = ctx;
  const screen = h('section', { class: 'screen screen-history' });
  root.append(screen);
  let alive = true;

  const playNow = () => h('button', { type: 'button', class: 'btn btn-primary', onClick: () => ctx.navigate('setup') }, 'Start a quiz');

  function header(history) {
    const count = history?.stats.attempts ?? 0;
    return h(
      'header',
      { class: 'screen-header screen-header-row' },
      h(
        'div',
        {},
        h('h1', {}, history ? `${history.player.name}'s history` : 'Your history'),
        h('p', { class: 'lede' }, 'Every finished quiz is saved in the quiz database.'),
      ),
      count > 0
        ? h(
            'button',
            {
              type: 'button',
              class: 'btn btn-ghost',
              onClick: async () => {
                const ok = await confirmDialog({
                  title: 'Clear all history?',
                  message: `This permanently deletes ${count} saved attempt${count === 1 ? '' : 's'}, including their leaderboard scores.`,
                  confirmText: 'Clear history',
                  danger: true,
                });
                if (!ok) return;
                try {
                  await api.clearHistory();
                  ctx.announce('History cleared.');
                  load();
                } catch (error) {
                  ctx.handleError(error);
                }
              },
            },
            'Clear history',
          )
        : null,
    );
  }

  async function load() {
    if (!ctx.player) {
      screen.replaceChildren(
        header(null),
        emptyState({ icon: '👋', title: 'Who is playing?', message: 'Enter your name on the setup screen and finish a quiz to build your history.', action: playNow() }),
      );
      return;
    }
    screen.replaceChildren(header(null), loadingState('Loading your history…'));
    let history;
    try {
      history = await api.history();
    } catch (error) {
      if (!alive) return;
      if (error.status === 401) return ctx.handleError(error);
      screen.replaceChildren(header(null), emptyState({ icon: '⚠️', title: "Couldn't load your history", message: error.message, action: null }));
      return;
    }
    if (!alive) return;
    render(history);
  }

  function render(history) {
    const { stats } = history;
    if (stats.attempts === 0) {
      screen.replaceChildren(
        header(history),
        emptyState({ icon: '📭', title: 'No quizzes yet', message: 'Finish a quiz and your scores, streaks and topic mastery will show up here.', action: playNow() }),
      );
      return;
    }

    const tiles = h(
      'section',
      { class: 'stat-grid', attrs: { 'aria-label': 'Lifetime statistics' } },
      statTile({ label: 'Quizzes taken', value: String(stats.attempts) }),
      statTile({ label: 'Average score', value: `${stats.averagePercentage}%` }),
      statTile({ label: 'Best score', value: `${stats.bestPercentage}%` }),
      statTile({ label: 'Questions answered', value: String(stats.questions), detail: `${stats.overallAccuracy}% correct overall` }),
    );

    const mastery = h(
      'section',
      { class: 'card' },
      h('h2', { class: 'card-title' }, 'Topic mastery'),
      accuracyBars(history.byTopic.map((row) => ({ ...row, prefix: row.icon })), {
        caption: 'Share of questions answered correctly, across all attempts',
      }),
    );

    const allTopics = ctx.catalog.topics.length;
    const rows = history.attempts.map((attempt) => {
      const { config } = attempt;
      const topicNames = config.topics.map((id) => ctx.getTopic(id)?.name ?? id);
      const topicsLabel = config.isRetry ? 'Retry of missed questions' : topicNames.length === allTopics ? 'All topics' : topicNames.join(', ');
      const timer =
        config.timerMode === TIMER_MODES.OFF
          ? 'Untimed'
          : config.timerMode === TIMER_MODES.QUESTION
            ? `${config.secondsPerQuestion}s per question`
            : `${formatClock(config.secondsPerQuestion * attempt.total * 1000)} total`;
      return h(
        'tr',
        {},
        h('td', {}, formatDate(attempt.finishedAt)),
        h('td', {}, topicsLabel, h('span', { class: 'cell-sub' }, `${DIFFICULTY_LABELS[config.difficulty]} · ${timer}`)),
        h('td', { class: 'num' }, `${attempt.correct}/${attempt.total}`),
        h('td', { class: 'num' }, h('strong', {}, `${attempt.percentage}%`), h('span', { class: 'cell-sub' }, `Grade ${attempt.grade}`)),
        h('td', { class: 'num' }, String(attempt.points)),
        h('td', { class: 'num' }, formatDuration(attempt.durationMs)),
        h(
          'td',
          {},
          h(
            'button',
            {
              type: 'button',
              class: 'btn btn-small btn-ghost',
              attrs: { 'aria-label': `Review quiz from ${formatDate(attempt.finishedAt)}` },
              onClick: () => ctx.navigate('results', { attemptId: attempt.id }),
            },
            'Review',
          ),
        ),
      );
    });

    const table = h(
      'section',
      { class: 'card' },
      h('h2', { class: 'card-title' }, 'Past attempts'),
      h(
        'div',
        { class: 'table-scroll', attrs: { tabindex: '0', role: 'region', 'aria-label': 'Past attempts table' } },
        h(
          'table',
          { class: 'data-table' },
          h(
            'thead',
            {},
            h(
              'tr',
              {},
              ['Date', 'Quiz', 'Correct', 'Score', 'Points', 'Time', ''].map((label, i) =>
                h('th', { scope: 'col', class: i >= 2 && i <= 5 ? 'num' : '' }, label),
              ),
            ),
          ),
          h('tbody', {}, rows),
        ),
      ),
    );

    screen.replaceChildren(header(history), tiles, mastery, table);
  }

  load();
  return () => {
    alive = false;
  };
}
