/**
 * Results screen: score hero, stat tiles, topic/difficulty breakdown and a full
 * question-by-question review, plus retake actions.
 */

import { END_REASONS, ITEM_STATUS, summarizeSession } from '../../core/quizEngine.js';
import { accuracyBars, chip, difficultyChip, scoreRing, statTile, statusPill } from '../components.js';
import { confirmDialog } from '../dialog.js';
import { formatDate, formatDuration, h } from '../dom.js';

const END_REASON_NOTES = {
  [END_REASONS.TIME_UP]: "Time ran out, so the quiz was submitted automatically. Questions you didn't reach are marked as skipped.",
  [END_REASONS.QUIT]: 'You ended this quiz early. Unanswered questions are marked as skipped.',
};

const REVIEW_FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'missed', label: 'Missed' },
  { value: 'correct', label: 'Correct' },
];

export function renderResultsScreen(root, ctx, { session, fresh = false }) {
  const { bank, storage } = ctx;
  const summary = summarizeSession(session, bank);
  const name = session.config.playerName;

  /* ---------- hero ---------- */

  const headline = fresh
    ? `${summary.grade.label}${name ? `, ${name}` : ''}!`
    : `Quiz review${name ? ` — ${name}` : ''}`;

  const hero = h(
    'header',
    { class: 'card results-hero' },
    scoreRing(summary.percentage),
    h(
      'div',
      { class: 'results-hero-text' },
      h('p', { class: 'eyebrow' }, fresh ? 'Quiz complete' : formatDate(session.finishedAt)),
      h('h1', {}, headline),
      h(
        'p',
        { class: 'results-line' },
        h('span', { class: ['grade-badge', `grade-${summary.grade.grade.toLowerCase()}`] }, `Grade ${summary.grade.grade}`),
        h('span', {}, `${summary.correct} of ${summary.total} correct`),
        h('span', {}, `${summary.points} / ${summary.maxPoints} pts`),
      ),
      END_REASON_NOTES[summary.endReason] ? h('p', { class: 'notice' }, END_REASON_NOTES[summary.endReason]) : null,
    ),
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
      accuracyBars(
        summary.byTopic.map((row) => ({ ...row, prefix: bank.getTopic(row.key)?.icon })),
        { caption: 'Accuracy by topic' },
      ),
      accuracyBars(summary.byDifficulty, { caption: 'Accuracy by difficulty' }),
    ),
  );

  /* ---------- review ---------- */

  const reviewCards = summary.review.map((entry) => reviewCard(entry, bank));
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

  /* ---------- actions ---------- */

  const missed = summary.missedQuestionIds;

  async function startGuarded(config, options) {
    if (storage.getActiveSession()) {
      const ok = await confirmDialog({
        title: 'Start a new quiz?',
        message: 'You have an unfinished quiz. Starting a new one will discard it.',
        confirmText: 'Start new quiz',
        danger: true,
      });
      if (!ok) return;
    }
    ctx.startQuiz(config, options);
  }

  const actions = h(
    'div',
    { class: 'results-actions' },
    h(
      'button',
      { type: 'button', class: 'btn btn-primary', dataset: { action: 'retake' }, onClick: () => startGuarded(session.config) },
      '↻ Retake quiz',
    ),
    missed.length > 0
      ? h(
          'button',
          {
            type: 'button',
            class: 'btn btn-secondary',
            dataset: { action: 'retry-missed' },
            onClick: () => startGuarded({ ...session.config, count: missed.length }, { questionIds: missed }),
          },
          `Retry ${missed.length} missed`,
        )
      : null,
    h('button', { type: 'button', class: 'btn btn-ghost', onClick: () => ctx.navigate('setup') }, 'New quiz'),
    h('button', { type: 'button', class: 'btn btn-ghost', onClick: () => ctx.navigate('history') }, 'History'),
  );

  root.append(h('section', { class: 'screen screen-results' }, hero, actions, tiles, breakdown, review));
  if (fresh) ctx.announce(`Quiz complete. You scored ${summary.percentage} percent, grade ${summary.grade.grade}.`);
  return null;
}

function reviewCard(entry, bank) {
  const topic = bank.getTopic(entry.topic);
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
    h('h3', { class: 'review-question' }, entry.question),
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
    h('p', { class: 'review-explanation' }, h('strong', {}, 'Why: '), entry.explanation),
    h(
      'p',
      { class: 'review-meta' },
      `${formatDuration(entry.timeSpentMs)} · ${entry.points > 0 ? '+' : ''}${entry.points} pts`,
    ),
  );
  return { el, status: entry.status };
}
