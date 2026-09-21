/**
 * Setup screen: player name, topic selection, difficulty, length, timer mode and
 * scoring options. Also offers to resume an unfinished quiz.
 */

import {
  DEFAULT_CONFIG,
  DIFFICULTY_FILTERS,
  DIFFICULTY_LABELS,
  QUESTION_COUNT_OPTIONS,
  SECONDS_PER_QUESTION_OPTIONS,
  TIMER_MODES,
} from '../../core/config.js';
import { confirmDialog } from '../dialog.js';
import { formatClock, h, richText } from '../dom.js';

const TIMER_MODE_OPTIONS = [
  { value: TIMER_MODES.QUESTION, label: 'Per question', hint: 'A fresh countdown for every question. Faster answers earn a speed bonus.' },
  { value: TIMER_MODES.SESSION, label: 'Whole quiz', hint: 'One countdown for the entire quiz. When it hits zero the quiz is submitted.' },
  { value: TIMER_MODES.OFF, label: 'No timer', hint: 'Take your time. Time spent is still recorded in your stats.' },
];

/** Restores a saved setup, dropping anything the current catalog no longer offers. */
function restoreConfig(saved, topics) {
  const topicIds = topics.map((t) => t.id);
  const c = { ...DEFAULT_CONFIG, topics: topicIds, ...(saved ?? {}) };
  const savedTopics = Array.isArray(c.topics) ? c.topics.filter((id) => topicIds.includes(id)) : [];
  return {
    topics: saved ? savedTopics : topicIds,
    difficulty: DIFFICULTY_FILTERS.includes(c.difficulty) ? c.difficulty : DEFAULT_CONFIG.difficulty,
    count: QUESTION_COUNT_OPTIONS.includes(c.count) ? c.count : DEFAULT_CONFIG.count,
    timerMode: Object.values(TIMER_MODES).includes(c.timerMode) ? c.timerMode : DEFAULT_CONFIG.timerMode,
    secondsPerQuestion: SECONDS_PER_QUESTION_OPTIONS.includes(c.secondsPerQuestion) ? c.secondsPerQuestion : DEFAULT_CONFIG.secondsPerQuestion,
    shuffle: typeof c.shuffle === 'boolean' ? c.shuffle : DEFAULT_CONFIG.shuffle,
    negativeMarking: typeof c.negativeMarking === 'boolean' ? c.negativeMarking : DEFAULT_CONFIG.negativeMarking,
  };
}

export function renderSetupScreen(root, ctx) {
  const { api, storage, catalog } = ctx;
  const { topics, rules } = catalog;
  const config = restoreConfig(storage.getPrefs().lastConfig, topics);
  let alive = true;
  let activeAttempt = null;

  /* ---------- header, resume banner, quick stats ---------- */

  const header = h(
    'header',
    { class: 'screen-header' },
    h('h1', {}, 'Ready to test your knowledge?'),
    h('p', { class: 'lede' }, 'Pick your topics and rules, then see how you score. Every answer comes with an explanation.'),
  );

  const resumeSlot = h('div');
  const statsSlot = h('div');

  function bannerFor(attempt) {
    const topicNames = attempt.config.topics.map((id) => ctx.getTopic(id)?.name).filter(Boolean);
    const summary = `${attempt.live.answered} of ${attempt.total} answered · ${topicNames.join(', ') || 'All topics'}`;
    if (attempt.status === 'finished') {
      // It ran out of time while the player was away; the server already scored it.
      return h(
        'section',
        { class: 'banner', attrs: { 'aria-label': 'Quiz ended while you were away' } },
        h('div', { class: 'banner-text' }, h('strong', {}, 'Your last quiz ran out of time while you were away'), h('span', {}, summary)),
        h(
          'div',
          { class: 'banner-actions' },
          h(
            'button',
            {
              type: 'button',
              class: 'btn btn-primary',
              onClick: () => {
                storage.clearActiveAttemptId();
                ctx.navigate('results', { attemptId: attempt.id });
              },
            },
            'See results',
          ),
        ),
      );
    }
    return h(
      'section',
      { class: 'banner', attrs: { 'aria-label': 'Unfinished quiz' } },
      h('div', { class: 'banner-text' }, h('strong', {}, 'You have an unfinished quiz'), h('span', {}, summary)),
      h(
        'div',
        { class: 'banner-actions' },
        h(
          'button',
          {
            type: 'button',
            class: 'btn btn-ghost',
            onClick: async () => {
              const ok = await confirmDialog({
                title: 'Discard unfinished quiz?',
                message: 'Your answers so far will be lost and this attempt will not be saved to history.',
                confirmText: 'Discard',
                danger: true,
              });
              if (!ok) return;
              try {
                await api.discardAttempt(attempt.id);
              } catch (error) {
                if (error.status !== 404) return ctx.handleError(error);
              }
              storage.clearActiveAttemptId();
              activeAttempt = null;
              resumeSlot.replaceChildren();
              ctx.announce('Unfinished quiz discarded.');
            },
          },
          'Discard',
        ),
        h(
          'button',
          { type: 'button', class: 'btn btn-primary', dataset: { action: 'resume' }, onClick: () => resume(attempt.id) },
          'Resume quiz',
        ),
      ),
    );
  }

  async function resume(attemptId) {
    try {
      const state = await api.getAttempt(attemptId);
      if (state.status === 'finished') {
        storage.clearActiveAttemptId();
        ctx.navigate('results', { attemptId, fresh: true });
      } else {
        ctx.navigate('quiz', { state });
      }
    } catch (error) {
      ctx.handleError(error);
    }
  }

  async function loadActiveAttempt() {
    const id = storage.getActiveAttemptId();
    if (!id || !ctx.player) return;
    try {
      const attempt = await api.getAttempt(id);
      if (!alive) return;
      activeAttempt = attempt.status === 'active' ? attempt : null;
      resumeSlot.replaceChildren(bannerFor(attempt));
    } catch (error) {
      if (error.status === 404 || error.status === 401) storage.clearActiveAttemptId();
    }
  }

  async function loadStats() {
    if (!ctx.player) return;
    try {
      const { stats } = await api.history();
      if (!alive || stats.attempts === 0) return;
      statsSlot.replaceChildren(
        h(
          'section',
          { class: 'stats-strip', attrs: { 'aria-label': 'Your stats' } },
          h('span', {}, h('strong', {}, String(stats.attempts)), stats.attempts === 1 ? ' quiz taken' : ' quizzes taken'),
          h('span', {}, h('strong', {}, `${stats.averagePercentage}%`), ' average'),
          h('span', {}, h('strong', {}, `${stats.bestPercentage}%`), ' best'),
          h('button', { type: 'button', class: 'link-button', onClick: () => ctx.navigate('history') }, 'View history →'),
        ),
      );
    } catch {
      /* stats are optional decoration */
    }
  }

  /* ---------- player ---------- */

  const nameInput = h('input', {
    id: 'player-name',
    type: 'text',
    class: 'text-input',
    value: ctx.player?.name ?? '',
    maxLength: rules.playerNameMax,
    autocomplete: 'nickname',
    required: true,
    placeholder: 'e.g. Alex',
    attrs: { 'aria-describedby': 'player-name-hint' },
    onInput: () => refresh(),
  });
  const playerCard = card(
    'Player',
    h('label', { class: 'field-label', htmlFor: 'player-name' }, 'Your name'),
    nameInput,
    h(
      'p',
      { class: 'hint', id: 'player-name-hint' },
      'Shown on the leaderboard. The first time you use a name, this browser claims it.',
    ),
  );

  /* ---------- topics ---------- */

  const topicInputs = new Map();
  const topicCountEls = new Map();
  const topicGrid = h(
    'div',
    { class: 'topic-grid' },
    topics.map((topic) => {
      const input = h('input', {
        type: 'checkbox',
        class: 'topic-input',
        name: 'topics',
        value: topic.id,
        checked: config.topics.includes(topic.id),
        onChange: () => {
          config.topics = topics.map((t) => t.id).filter((id) => topicInputs.get(id).checked);
          refresh();
        },
      });
      topicInputs.set(topic.id, input);
      const count = h('span', { class: 'topic-count' });
      topicCountEls.set(topic.id, count);
      return h(
        'label',
        { class: 'topic-card', dataset: { topic: topic.id } },
        input,
        h('span', { class: 'topic-icon', attrs: { 'aria-hidden': 'true' } }, topic.icon),
        h('span', { class: 'topic-body' }, h('span', { class: 'topic-name' }, topic.name), h('span', { class: 'topic-desc' }, ...richText(topic.description)), count),
        h('span', { class: 'topic-check', attrs: { 'aria-hidden': 'true' } }, '✓'),
      );
    }),
  );

  const setAllTopics = (checked) => {
    for (const input of topicInputs.values()) input.checked = checked;
    config.topics = checked ? topics.map((t) => t.id) : [];
    refresh();
  };

  const topicsCard = fieldsetCard(
    'Topics',
    h(
      'div',
      { class: 'fieldset-tools' },
      h('button', { type: 'button', class: 'link-button', onClick: () => setAllTopics(true) }, 'Select all'),
      h('button', { type: 'button', class: 'link-button', onClick: () => setAllTopics(false) }, 'Clear'),
    ),
    topicGrid,
  );

  /* ---------- difficulty & length ---------- */

  const points = rules.difficultyPoints;
  const difficultyCard = fieldsetCard(
    'Difficulty',
    segmented(
      'difficulty',
      DIFFICULTY_FILTERS.map((value) => ({ value, label: DIFFICULTY_LABELS[value] })),
      config.difficulty,
      (value) => {
        config.difficulty = value;
        refresh();
      },
    ),
    h('p', { class: 'hint' }, `Points per correct answer: ${points.easy} easy · ${points.medium} medium · ${points.hard} hard.`),
  );

  const availabilityHint = h('p', { class: 'hint', attrs: { 'aria-live': 'polite' } });
  const lengthCard = fieldsetCard(
    'Number of questions',
    segmented(
      'count',
      QUESTION_COUNT_OPTIONS.map((value) => ({ value: String(value), label: String(value) })),
      String(config.count),
      (value) => {
        config.count = Number(value);
        refresh();
      },
    ),
    availabilityHint,
  );

  /* ---------- timer ---------- */

  const timerHint = h('p', { class: 'hint' });
  const secondsSelect = h(
    'select',
    {
      id: 'seconds-per-question',
      class: 'select-input',
      onChange: (event) => {
        config.secondsPerQuestion = Number(event.target.value);
        refresh();
      },
    },
    SECONDS_PER_QUESTION_OPTIONS.map((s) => h('option', { value: String(s), selected: s === config.secondsPerQuestion }, `${s} seconds`)),
  );
  const secondsField = h(
    'div',
    { class: 'inline-field' },
    h('label', { class: 'field-label', htmlFor: 'seconds-per-question' }, 'Time per question'),
    secondsSelect,
  );
  const timerCard = fieldsetCard(
    'Timer',
    segmented(
      'timerMode',
      TIMER_MODE_OPTIONS.map(({ value, label }) => ({ value, label })),
      config.timerMode,
      (value) => {
        config.timerMode = value;
        refresh();
      },
    ),
    secondsField,
    timerHint,
  );

  /* ---------- options ---------- */

  const optionsCard = fieldsetCard(
    'Options',
    toggle('Shuffle questions & answers', 'Off: questions run from easy to hard with answers in their original order.', config.shuffle, (checked) => {
      config.shuffle = checked;
    }),
    toggle(
      'Negative marking',
      `Wrong answers cost ${Math.round(rules.negativeMarkRatio * 100)}% of the question's points. Your total never drops below zero.`,
      config.negativeMarking,
      (checked) => {
        config.negativeMarking = checked;
      },
    ),
  );

  /* ---------- submit ---------- */

  const summaryText = h('p', { class: 'setup-summary' });
  const errorText = h('p', { class: 'form-error', attrs: { role: 'alert' } });
  const startButton = h('button', { type: 'submit', class: 'btn btn-primary btn-large', dataset: { action: 'start' } }, 'Start quiz →');
  let busy = false;

  const form = h(
    'form',
    {
      class: 'setup-form',
      noValidate: true,
      onSubmit: async (event) => {
        event.preventDefault();
        if (busy) return;
        const name = nameInput.value.trim();
        if (!name) {
          errorText.textContent = 'Enter your name to start.';
          nameInput.focus();
          return;
        }
        if (config.topics.length === 0 || availableCount() === 0) {
          errorText.textContent = config.topics.length === 0 ? 'Choose at least one topic.' : 'No questions match these settings.';
          return;
        }
        if (activeAttempt) {
          const ok = await confirmDialog({
            title: 'Start a new quiz?',
            message: 'You have an unfinished quiz. Starting a new one will discard it.',
            confirmText: 'Start new quiz',
            danger: true,
          });
          if (!ok) return;
        }
        setBusy(true);
        try {
          await ctx.ensurePlayer(name);
          await ctx.startQuiz({ ...config }); // the server caps count at what is available
        } catch (error) {
          if (!alive) return;
          if (error.code === 'name_taken') {
            errorText.textContent = `${error.message} (It belongs to another browser.)`;
            nameInput.focus();
          } else if (error.status === 400) {
            errorText.textContent = error.message;
          } else {
            ctx.handleError(error);
          }
        } finally {
          if (alive) setBusy(false);
        }
      },
    },
    playerCard,
    topicsCard,
    h('div', { class: 'card-row' }, difficultyCard, lengthCard),
    timerCard,
    optionsCard,
    h('div', { class: 'setup-footer' }, h('div', {}, summaryText, errorText), startButton),
  );

  function setBusy(value) {
    busy = value;
    startButton.disabled = value || !isReady();
    startButton.textContent = value ? 'Starting…' : 'Start quiz →';
  }

  function countFor(topic) {
    return config.difficulty === 'mixed'
      ? topic.counts.easy + topic.counts.medium + topic.counts.hard
      : topic.counts[config.difficulty];
  }

  function availableCount() {
    return topics.filter((t) => config.topics.includes(t.id)).reduce((sum, t) => sum + countFor(t), 0);
  }

  function isReady() {
    return config.topics.length > 0 && availableCount() > 0;
  }

  function refresh() {
    for (const topic of topics) {
      const n = countFor(topic);
      topicCountEls.get(topic.id).textContent = `${n} question${n === 1 ? '' : 's'}`;
    }

    const available = availableCount();
    const count = Math.min(config.count, available);
    if (config.topics.length === 0) {
      availabilityHint.textContent = 'Select at least one topic.';
    } else if (available < config.count) {
      availabilityHint.textContent = `Only ${available} question${available === 1 ? '' : 's'} match — your quiz will have ${available}.`;
    } else {
      availabilityHint.textContent = `${available} questions available for this selection.`;
    }

    const mode = TIMER_MODE_OPTIONS.find((m) => m.value === config.timerMode);
    secondsField.hidden = config.timerMode === TIMER_MODES.OFF;
    let timing = mode.hint;
    if (config.timerMode === TIMER_MODES.SESSION && count > 0) {
      timing += ` Total: ${formatClock(count * config.secondsPerQuestion * 1000)}.`;
    }
    if (config.timerMode === TIMER_MODES.QUESTION) {
      timing += ` (Up to +${Math.round(rules.speedBonusRatio * 100)}% points.)`;
    }
    timerHint.textContent = timing;

    startButton.disabled = busy || !isReady();
    errorText.textContent = '';
    summaryText.textContent = isReady()
      ? `${count} question${count === 1 ? '' : 's'} · ${config.difficulty === 'mixed' ? 'mixed difficulty' : DIFFICULTY_LABELS[config.difficulty].toLowerCase()} · ${
          config.timerMode === TIMER_MODES.OFF ? 'untimed' : `${config.secondsPerQuestion}s per question`
        }`
      : 'Choose at least one topic to begin.';
  }

  refresh();
  root.append(h('section', { class: 'screen screen-setup' }, header, resumeSlot, statsSlot, form));
  loadActiveAttempt();
  loadStats();

  return () => {
    alive = false;
  };
}

/* ---------- small form builders ---------- */

function card(title, ...children) {
  return h('section', { class: 'card' }, h('h2', { class: 'card-title' }, title), children);
}

function fieldsetCard(legend, ...children) {
  return h('fieldset', { class: 'card' }, h('legend', { class: 'card-title' }, legend), children);
}

/** Radio group styled as a segmented control (keyboard arrows work natively). */
function segmented(name, options, selected, onChange) {
  return h(
    'div',
    { class: 'segmented' },
    options.map(({ value, label }) =>
      h(
        'label',
        { class: 'segment' },
        h('input', {
          type: 'radio',
          name,
          value,
          checked: value === selected,
          onChange: (event) => event.target.checked && onChange(value),
        }),
        h('span', {}, label),
      ),
    ),
  );
}

function toggle(label, description, checked, onChange) {
  return h(
    'label',
    { class: 'toggle' },
    h('input', { type: 'checkbox', attrs: { role: 'switch' }, checked, onChange: (event) => onChange(event.target.checked) }),
    h('span', { class: 'toggle-track', attrs: { 'aria-hidden': 'true' } }),
    h('span', { class: 'toggle-text' }, h('span', { class: 'toggle-label' }, label), h('span', { class: 'hint' }, description)),
  );
}
