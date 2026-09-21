/**
 * Results screen: score hero, stat tiles, topic/difficulty breakdown and a full
 * question-by-question review, plus retake actions. Data comes from
 * GET /api/attempts/{id}/results (scored by the server).
 */

import { END_REASONS, ITEM_STATUS } from '../../core/config.js';
import { accuracyBars, chip, difficultyChip, emptyState, loadingState, scoreRing, statTile, statusPill } from '../components.js';
import { formatDate, formatDuration, h, richText } from '../dom.js';

const END_REASON_NOTES = {
  [END_REASONS.TIME_UP]: "Time ran out, so the quiz was submitted automatically. Questions you didn't reach are marked as skipped.",
  [END_REASONS.QUIT]: 'You ended this quiz early. Unanswered questions are marked as skipped, and it does not count for the leaderboard.',
};

const REVIEW_FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'missed', label: 'Missed' },
  { value: 'correct', label: 'Correct' },
];

export function renderResultsScreen(root, ctx, { attemptId, fresh = false }) {
  const screen = h('section', { class: 'screen screen-results' }, loadingState('Loading your results…'));
  root.append(screen);
  let alive = true;

  ctx.api
    .results(attemptId)
    .then((summary) => {
      if (!alive) return;
      screen.replaceChildren(...buildResults(summary, ctx, fresh));
      if (fresh) ctx.announce(`Quiz complete. You scored ${summary.percentage} percent, grade ${summary.grade.grade}.`);
    })
    .catch((error) => {
      if (!alive) return;
      screen.replaceChildren(
        emptyState({
          icon: '⚠️',
          title: "Couldn't load these results",
          message: error.message,
          action: h('button', { type: 'button', class: 'btn btn-primary', onClick: () => ctx.navigate('history') }, 'Back to history'),
        }),
      );
    });

  return () => {
    alive = false;
  };
}

function buildResults(summary, ctx, fresh) {
  const name = ctx.player?.name;

  /* ---------- hero ---------- */

  const hero = h(
    'header',
    { class: 'card results-hero' },
    scoreRing(summary.percentage),
    h(
      'div',
      { class: 'results-hero-text' },
      h('p', { class: 'eyebrow' }, fresh ? 'Quiz complete' : formatDate(summary.finishedAt)),
      h('h1', {}, fresh ? `${summary.grade.label}${name ? `, ${name}` : ''}!` : 'Quiz review'),
      h(
        'p',
        { class: 'results-line' },
        h('span', { class: 'grade-badge' }, `Grade ${summary.grade.grade}`),
        h('span', {}, `${summary.correct} of ${summary.total} correct`),
        h('span', {}, `${summary.points} / ${summary.maxPoints} pts`),
      ),
      END_REASON_NOTES[summary.endReason] ? h('p', { class: 'notice' }, END_REASON_NOTES[summary.endReason]) : null,
    ),
  );

  /* ---------- actions ---------- */

  const { isRetry, ...config } = summary.config;
  const missed = summary.missedQuestionIds;
  let starting = false;

  async function start(quizConfig, options) {
    if (starting) return;
    starting = true;
    try {
      await ctx.startQuiz(quizConfig, options);
    } catch (error) {
      ctx.handleError(error);
    } finally {
      starting = false;
    }
  }

  const actions = h(
    'div',
    { class: 'results-actions' },
    h('button', { type: 'button', class: 'btn btn-primary', dataset: { action: 'retake' }, onClick: () => start(config) }, '↻ Retake quiz'),
    missed.length > 0
      ? h(
          'button',
          {
            type: 'button',
            class: 'btn btn-secondary',
            dataset: { action: 'retry-missed' },
            onClick: () => start({ ...config, count: missed.length }, { questionIds: missed }),
          },
          `Retry ${missed.length} missed`,
        )
      : null,
    h('button', { type: 'button', class: 'btn btn-ghost', onClick: () => ctx.navigate('setup') }, 'New quiz'),
    h('button', { type: 'button', class: 'btn btn-ghost', onClick: () => ctx.navigate('leaderboard') }, 'Leaderboard'),
  );

  /* ---------- stat tiles ---------- */

  const tiles = h(
    'section',
    { class: 'stat-grid', attrs: { 'aria-label': 'Summary statistics' } },
    statTile({ label: 'Correct', value: String(summary.correct), detail: `${summary.accuracy}% of answered` }),
    statTile({ label: 'Incorrect', value: String(summary.wrong) }),
    statTile({ label: 'Timed out', value: String(summary.timedOut) }),
    statTile({ label: 'Skipped', value: String(summary.skipped) }),
    statTile({ label: 'Best streak', value: String(summary.bestStreak) }),
    statTile({ label: 'Total time', value: formatDuration(summary.durationMs), detail: `${formatDuration(summary.averageTimeMs)} per question` }),
  );

  /* ---------- breakdown ---------- */

  const breakdown = h(
    'section',
    { class: 'card breakdown' },
    h('h2', { class: 'card-title' }, 'Performance breakdown'),
    h(
      'div',
      { class: 'breakdown-grid' },
      accuracyBars(summary.byTopic.map((row) => ({ ...row, prefix: row.icon })), { caption: 'Accuracy by topic' }),
      accuracyBars(summary.byDifficulty, { caption: 'Accuracy by difficulty' }),
    ),
  );

  /* ---------- review ---------- */

  const reviewCards = summary.review.map((entry) => reviewCard(entry, ctx));
  const reviewList = h('ol', { class: 'review-list' }, reviewCards.map(({ el }) => el));
  const reviewEmpty = h('p', { class: 'hint review-empty', hidden: true });

  function applyFilter(filter) {
    let visible = 0;
    for (const { el, status } of reviewCards) {
      const show = filter === 'all' || (filter === 'correct' ? status === ITEM_STATUS.CORRECT : status !== ITEM_STATUS.CORRECT);
      el.hidden = !show;
      if (show) visible += 1;
    }
    reviewEmpty.hidden = visible > 0;
    reviewEmpty.textContent = filter === 'missed' ? 'Nothing missed — every answer was correct. 🎉' : 'No correct answers this time.';
  }

  const filterGroup = h(
    'div',
    { class: 'segmented segmented-small', attrs: { role: 'radiogroup', 'aria-label': 'Filter questions' } },
    REVIEW_FILTERS.map(({ value, label }) =>
      h(
        'label',
        { class: 'segment' },
        h('input', { type: 'radio', name: 'review-filter', value, checked: value === 'all', onChange: () => applyFilter(value) }),
        h('span', {}, label),
      ),
    ),
  );

  const review = h(
    'section',
    { class: 'review' },
    h('div', { class: 'review-header' }, h('h2', {}, 'Review your answers'), filterGroup),
    reviewList,
    reviewEmpty,
  );

  return [hero, actions, tiles, breakdown, review];
}

function reviewCard(entry, ctx) {
  const topic = ctx.getTopic(entry.topic);
  const el = h(
    'li',
    { class: ['card', 'review-card', `status-${entry.status}`] },
    h(
      'div',
      { class: 'review-card-head' },
      h('span', { class: 'review-number' }, `Q${entry.number}`),
      chip(`${topic?.icon ?? ''} ${topic?.name ?? entry.topic}`.trim()),
      difficultyChip(entry.difficulty),
      h('span', { class: 'review-spacer' }),
      statusPill(entry.status),
    ),
    h('h3', { class: 'review-question' }, ...richText(entry.question)),
    entry.code ? h('pre', { class: 'code-block' }, h('code', {}, entry.code)) : null,
    h(
      'ul',
      { class: 'review-options' },
      entry.options.map((option) =>
        h(
          'li',
          { class: ['review-option', option.isCorrect && 'is-correct', option.isSelected && !option.isCorrect && 'is-wrong'] },
          h('span', { class: 'review-option-text' }, option.text),
          option.isCorrect ? h('span', { class: 'review-tag' }, '✓ Correct answer') : null,
          option.isSelected && !option.isCorrect ? h('span', { class: 'review-tag' }, '✗ Your answer') : null,
        ),
      ),
    ),
    h('p', { class: 'review-explanation' }, h('strong', {}, 'Why: '), ...richText(entry.explanation)),
    h('p', { class: 'review-meta' }, `${formatDuration(entry.timeSpentMs)} · ${entry.points > 0 ? '+' : ''}${entry.points} pts`),
  );
  return { el, status: entry.status };
}
