/**
 * Reusable presentational components (pure DOM factories).
 */

import { DIFFICULTY_META, TIMER_DANGER_FRACTION, TIMER_WARNING_FRACTION } from '../core/config.js';
import { ITEM_STATUS } from '../core/quizEngine.js';
import { formatClock, h, svg } from './dom.js';

/** Status is never conveyed by colour alone: every status has an icon and a label. */
export const STATUS_META = Object.freeze({
  [ITEM_STATUS.CORRECT]: { label: 'Correct', icon: '✓', tone: 'good' },
  [ITEM_STATUS.WRONG]: { label: 'Incorrect', icon: '✗', tone: 'critical' },
  [ITEM_STATUS.TIMEOUT]: { label: 'Timed out', icon: '⏱', tone: 'warning' },
  [ITEM_STATUS.SKIPPED]: { label: 'Skipped', icon: '–', tone: 'muted' },
  [ITEM_STATUS.PENDING]: { label: 'Unanswered', icon: '–', tone: 'muted' },
});

export function statusPill(status) {
  const meta = STATUS_META[status] ?? STATUS_META[ITEM_STATUS.PENDING];
  return h(
    'span',
    { class: ['pill', `tone-${meta.tone}`] },
    h('span', { class: 'pill-icon', attrs: { 'aria-hidden': 'true' } }, meta.icon),
    meta.label,
  );
}

export function chip(text, extraClass = '') {
  return h('span', { class: ['chip', extraClass] }, text);
}

export function difficultyChip(difficulty) {
  return chip(DIFFICULTY_META[difficulty]?.label ?? difficulty, `chip-${difficulty}`);
}

export function statTile({ label, value, detail = null }) {
  return h(
    'div',
    { class: 'stat-tile' },
    h('span', { class: 'stat-label' }, label),
    h('span', { class: 'stat-value' }, value),
    detail ? h('span', { class: 'stat-detail' }, detail) : null,
  );
}

/**
 * Circular countdown. `update` takes the Timer state; colour steps from normal
 * to warning to danger as time runs out (with a text label, never colour alone).
 */
export function timerRing({ label = 'Time left' } = {}) {
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const progress = svg('circle', {
    class: 'timer-ring-progress',
    cx: 32,
    cy: 32,
    r: radius,
    'stroke-dasharray': circumference.toFixed(2),
    'stroke-dashoffset': 0,
  });
  const text = h('span', { class: 'timer-ring-text' }, '0:00');
  const el = h(
    'div',
    { class: 'timer-ring', attrs: { role: 'timer', 'aria-label': label } },
    svg(
      'svg',
      { viewBox: '0 0 64 64', 'aria-hidden': 'true' },
      svg('circle', { class: 'timer-ring-track', cx: 32, cy: 32, r: radius }),
      progress,
    ),
    text,
  );

  return {
    el,
    update({ remainingMs, fraction }) {
      text.textContent = formatClock(remainingMs);
      progress.setAttribute('stroke-dashoffset', (circumference * (1 - fraction)).toFixed(2));
      el.dataset.level = fraction <= TIMER_DANGER_FRACTION ? 'danger' : fraction <= TIMER_WARNING_FRACTION ? 'warning' : 'normal';
    },
  };
}

/**
 * Big percentage ring used as the results "hero number".
 */
export function scoreRing(percentage) {
  const radius = 54;
  const circumference = 2 * Math.PI * radius;
  const clamped = Math.min(100, Math.max(0, percentage));
  return h(
    'div',
    { class: 'score-ring', attrs: { role: 'img', 'aria-label': `Score ${clamped} percent` } },
    svg(
      'svg',
      { viewBox: '0 0 128 128', 'aria-hidden': 'true' },
      svg('circle', { class: 'score-ring-track', cx: 64, cy: 64, r: radius }),
      svg('circle', {
        class: 'score-ring-progress',
        cx: 64,
        cy: 64,
        r: radius,
        'stroke-dasharray': circumference.toFixed(2),
        'stroke-dashoffset': (circumference * (1 - clamped / 100)).toFixed(2),
      }),
    ),
    h('span', { class: 'score-ring-value' }, `${clamped}%`),
  );
}

/**
 * Horizontal accuracy bars — one series, one hue, value at the bar tip, and a
 * hover/focus tooltip with the exact counts.
 * @param {{ label: string, correct: number, total: number, percentage: number, prefix?: string }[]} rows
 */
export function accuracyBars(rows, { caption }) {
  return h(
    'figure',
    { class: 'bars' },
    h('figcaption', { class: 'bars-caption' }, caption),
    h(
      'ul',
      { class: 'bars-list' },
      rows.map((row) =>
        h(
          'li',
          {
            class: 'bar-row',
            attrs: { tabindex: '0', 'aria-label': `${row.label}: ${row.percentage}% (${row.correct} of ${row.total} correct)` },
          },
          h('span', { class: 'bar-label' }, row.prefix ? `${row.prefix} ${row.label}` : row.label),
          h(
            'span',
            { class: 'bar-track' },
            // The plot has a fixed extent so bar length stays proportional to the value;
            // the label sits in its own column after it.
            h('span', { class: 'bar-plot' }, h('span', { class: 'bar-fill', style: { width: `${row.percentage}%` } })),
            h('span', { class: 'bar-value' }, `${row.percentage}%`),
          ),
          h('span', { class: 'bar-tooltip', attrs: { role: 'tooltip' } }, `${row.correct} of ${row.total} correct`),
        ),
      ),
    ),
  );
}

export function emptyState({ icon, title, message, action = null }) {
  return h(
    'div',
    { class: 'empty-state' },
    h('span', { class: 'empty-icon', attrs: { 'aria-hidden': 'true' } }, icon),
    h('h2', {}, title),
    h('p', {}, message),
    action,
  );
}
