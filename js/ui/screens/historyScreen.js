/**
 * History screen: lifetime stats, topic mastery and a log of past attempts
 * (each can be reopened for a full review).
 */

import { DIFFICULTY_META, TIMER_MODES } from '../../core/config.js';
import { aggregateHistory, sessionFromHistoryEntry } from '../../core/stats.js';
import { accuracyBars, emptyState, statTile } from '../components.js';
import { confirmDialog } from '../dialog.js';
import { formatClock, formatDate, formatDuration, h } from '../dom.js';

export function renderHistoryScreen(root, ctx) {
  const { bank, storage } = ctx;
  const screen = h('section', { class: 'screen screen-history' });
  root.append(screen);

  function render() {
    const history = storage.getHistory();
    const stats = aggregateHistory(history, bank.topics);

    const header = h(
      'header',
      { class: 'screen-header screen-header-row' },
      h('div', {}, h('h1', {}, 'Your history'), h('p', { class: 'lede' }, 'Every finished quiz is saved on this device.')),
      history.length > 0
        ? h(
            'button',
            {
              type: 'button',
              class: 'btn btn-ghost',
              onClick: async () => {
                const ok = await confirmDialog({
                  title: 'Clear all history?',
                  message: `This permanently deletes ${history.length} saved attempt${history.length === 1 ? '' : 's'} from this device.`,
                  confirmText: 'Clear history',
                  danger: true,
                });
                if (!ok) return;
                storage.clearHistory();
                render();
                ctx.announce('History cleared.');
              },
            },
            'Clear history',
          )
        : null,
    );

    if (history.length === 0) {
      screen.replaceChildren(
        header,
        emptyState({
          icon: '📭',
          title: 'No quizzes yet',
          message: 'Finish a quiz and your scores, streaks and topic mastery will show up here.',
          action: h('button', { type: 'button', class: 'btn btn-primary', onClick: () => ctx.navigate('setup') }, 'Start a quiz'),
        }),
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
      accuracyBars(
        stats.byTopic.map((row) => ({ ...row, prefix: bank.getTopic(row.key)?.icon })),
        { caption: 'Share of questions answered correctly, across all attempts' },
      ),
    );

    const rows = history.map((entry) => {
      const s = entry.summary;
      const topicNames = entry.config.topics.map((id) => bank.getTopic(id)?.name ?? id);
      const topicsLabel = topicNames.length === bank.topics.length ? 'All topics' : topicNames.join(', ');
      const difficulty = entry.config.difficulty === 'mixed' ? 'Mixed' : DIFFICULTY_META[entry.config.difficulty]?.label;
      const timer =
        entry.config.timerMode === TIMER_MODES.OFF
          ? 'Untimed'
          : entry.config.timerMode === TIMER_MODES.QUESTION
            ? `${entry.config.secondsPerQuestion}s per question`
            : `${formatClock(entry.config.secondsPerQuestion * s.total * 1000)} total`;
      return h(
        'tr',
        {},
        h('td', {}, formatDate(entry.finishedAt), entry.playerName ? h('span', { class: 'cell-sub' }, entry.playerName) : null),
        h('td', {}, topicsLabel, h('span', { class: 'cell-sub' }, `${difficulty} · ${timer}`)),
        h('td', { class: 'num' }, `${s.correct}/${s.total}`),
        h('td', { class: 'num' }, h('strong', {}, `${s.percentage}%`), h('span', { class: 'cell-sub' }, `Grade ${s.grade}`)),
        h('td', { class: 'num' }, String(s.points)),
        h('td', { class: 'num' }, formatDuration(s.durationMs)),
        h(
          'td',
          {},
          h(
            'button',
            {
              type: 'button',
              class: 'btn btn-small btn-ghost',
              attrs: { 'aria-label': `Review quiz from ${formatDate(entry.finishedAt)}` },
              onClick: () => ctx.navigate('results', { session: sessionFromHistoryEntry(entry) }),
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
          { class: 'history-table' },
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

    screen.replaceChildren(header, tiles, mastery, table);
  }

  render();
  return null;
}
