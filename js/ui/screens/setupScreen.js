/**
 * Setup screen: player name, topic selection, difficulty, length, timer mode and
 * scoring options. Also offers to resume an unfinished quiz.
 */

import {
  DEFAULT_CONFIG,
  DIFFICULTY_FILTERS,
  DIFFICULTY_META,
  NEGATIVE_MARK_RATIO,
  PLAYER_NAME_MAX_LENGTH,
  QUESTION_COUNT_OPTIONS,
  SECONDS_PER_QUESTION_OPTIONS,
  SPEED_BONUS_RATIO,
  TIMER_MODES,
} from '../../core/config.js';
import { QuizError, normalizeConfig } from '../../core/quizEngine.js';
import { aggregateHistory } from '../../core/stats.js';
import { confirmDialog } from '../dialog.js';
import { formatClock, formatDate, h } from '../dom.js';

const TIMER_MODE_OPTIONS = [
  { value: TIMER_MODES.QUESTION, label: 'Per question', hint: 'A fresh countdown for every question. Faster answers earn a speed bonus.' },
  { value: TIMER_MODES.SESSION, label: 'Whole quiz', hint: 'One countdown for the entire quiz. When it hits zero the quiz is submitted.' },
  { value: TIMER_MODES.OFF, label: 'No timer', hint: 'Take your time. Time spent is still recorded in your stats.' },
];

export function renderSetupScreen(root, ctx) {
  const { bank, storage } = ctx;
  const saved = storage.getPrefs().lastConfig;
  const validTopicIds = new Set(bank.topics.map((t) => t.id));
  const config = normalizeConfig({
    ...DEFAULT_CONFIG,
    topics: bank.topics.map((t) => t.id),
    ...saved,
  });
  config.topics = config.topics.filter((id) => validTopicIds.has(id));
  if (!QUESTION_COUNT_OPTIONS.includes(config.count)) config.count = DEFAULT_CONFIG.count;

  /* ---------- header, resume banner, quick stats ---------- */

  const header = h(
    'header',
    { class: 'screen-header' },
    h('h1', {}, 'Ready to test your knowledge?'),
    h('p', { class: 'lede' }, 'Pick your topics and rules, then see how you score. Every answer comes with an explanation.'),
  );

  const resumeSlot = h('div');
  function renderResumeBanner() {
    const active = storage.getActiveSession();
    resumeSlot.replaceChildren();
    if (!active) return;
    const answered = active.items.filter((item) => item.status !== 'pending').length;
    const topicNames = active.config.topics.map((id) => bank.getTopic(id)?.name).filter(Boolean);
    resumeSlot.append(
      h(
        'section',
        { class: 'banner', attrs: { 'aria-label': 'Unfinished quiz' } },
        h(
          'div',
          { class: 'banner-text' },
          h('strong', {}, 'You have an unfinished quiz'),
          h(
            'span',
            {},
            `${answered} of ${active.items.length} answered · ${topicNames.join(', ') || 'All topics'} · started ${formatDate(active.createdAt)}`,
          ),
        ),
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
                storage.clearActiveSession();
                renderResumeBanner();
                ctx.announce('Unfinished quiz discarded.');
              },
            },
            'Discard',
          ),
          h(
            'button',
            { type: 'button', class: 'btn btn-primary', dataset: { action: 'resume' }, onClick: () => ctx.navigate('quiz', { session: active }) },
            'Resume quiz',
          ),
        ),
      ),
    );
  }
  renderResumeBanner();

  const history = storage.getHistory();
  const stats = aggregateHistory(history, bank.topics);
  const statsStrip =
    history.length > 0
      ? h(
          'section',
          { class: 'stats-strip', attrs: { 'aria-label': 'Your stats' } },
          h('span', {}, h('strong', {}, String(stats.attempts)), stats.attempts === 1 ? ' quiz taken' : ' quizzes taken'),
          h('span', {}, h('strong', {}, `${stats.averagePercentage}%`), ' average'),
          h('span', {}, h('strong', {}, `${stats.bestPercentage}%`), ' best'),
          h('button', { type: 'button', class: 'link-button', onClick: () => ctx.navigate('history') }, 'View history →'),
        )
      : null;

  /* ---------- player ---------- */

  const nameInput = h('input', {
    id: 'player-name',
    type: 'text',
    class: 'text-input',
    value: config.playerName,
    maxLength: PLAYER_NAME_MAX_LENGTH,
    autocomplete: 'nickname',
    placeholder: 'e.g. Alex',
    onInput: (event) => {
      config.playerName = event.target.value;
    },
  });
  const playerCard = card(
    'Player',
    h('label', { class: 'field-label', htmlFor: 'player-name' }, 'Your name ', h('span', { class: 'optional' }, '(optional)')),
    nameInput,
  );

  /* ---------- topics ---------- */

  const topicInputs = new Map();
  const topicCountEls = new Map();
  const topicGrid = h(
    'div',
    { class: 'topic-grid' },
    bank.topics.map((topic) => {
      const input = h('input', {
        type: 'checkbox',
        class: 'topic-input',
        name: 'topics',
        value: topic.id,
        checked: config.topics.includes(topic.id),
        onChange: () => {
          config.topics = bank.topics.map((t) => t.id).filter((id) => topicInputs.get(id).checked);
          refresh();
        },
      });
      topicInputs.set(topic.id, input);
      const count = h('span', { class: 'topic-count' });
      topicCountEls.set(topic.id, count);
      return h(
        'label',
        { class: 'topic-card' },
        input,
        h('span', { class: 'topic-icon', attrs: { 'aria-hidden': 'true' } }, topic.icon),
        h('span', { class: 'topic-body' }, h('span', { class: 'topic-name' }, topic.name), h('span', { class: 'topic-desc' }, topic.description), count),
        h('span', { class: 'topic-check', attrs: { 'aria-hidden': 'true' } }, '✓'),
      );
    }),
  );

  const setAllTopics = (checked) => {
    for (const input of topicInputs.values()) input.checked = checked;
    config.topics = checked ? bank.topics.map((t) => t.id) : [];
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

  const difficultyGroup = segmented(
    'difficulty',
    DIFFICULTY_FILTERS.map((value) => ({ value, label: value === 'mixed' ? 'Mixed' : DIFFICULTY_META[value].label })),
    config.difficulty,
    (value) => {
      config.difficulty = value;
      refresh();
    },
  );
  const difficultyCard = fieldsetCard(
    'Difficulty',
    difficultyGroup,
    h(
      'p',
      { class: 'hint' },
      `Points per correct answer: ${DIFFICULTY_META.easy.points} easy · ${DIFFICULTY_META.medium.points} medium · ${DIFFICULTY_META.hard.points} hard.`,
    ),
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
  if (!SECONDS_PER_QUESTION_OPTIONS.includes(config.secondsPerQuestion)) {
    config.secondsPerQuestion = DEFAULT_CONFIG.secondsPerQuestion;
    secondsSelect.value = String(config.secondsPerQuestion);
  }
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
      `Wrong answers cost ${Math.round(NEGATIVE_MARK_RATIO * 100)}% of the question's points. Your total never drops below zero.`,
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

  const form = h(
    'form',
    {
      class: 'setup-form',
      noValidate: true,
      onSubmit: async (event) => {
        event.preventDefault();
        const available = availableCount();
        if (config.topics.length === 0 || available === 0) {
          refresh();
          errorText.textContent = config.topics.length === 0 ? 'Choose at least one topic.' : 'No questions match these settings.';
          return;
        }
        if (storage.getActiveSession()) {
          const ok = await confirmDialog({
            title: 'Start a new quiz?',
            message: 'You have an unfinished quiz. Starting a new one will discard it.',
            confirmText: 'Start new quiz',
            danger: true,
          });
          if (!ok) return;
        }
        try {
          ctx.startQuiz({ ...config }); // the engine caps count at what is available
        } catch (error) {
          if (!(error instanceof QuizError)) throw error;
          errorText.textContent = error.message;
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

  function availableCount() {
    return config.topics.length === 0 ? 0 : bank.filter({ topics: config.topics, difficulty: config.difficulty }).length;
  }

  function refresh() {
    const counts = bank.countByTopic(config.difficulty);
    for (const [id, el] of topicCountEls) {
      const n = counts[id] ?? 0;
      el.textContent = `${n} question${n === 1 ? '' : 's'}`;
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
      timing += ` (Up to +${Math.round(SPEED_BONUS_RATIO * 100)}% points.)`;
    }
    timerHint.textContent = timing;

    const ready = config.topics.length > 0 && available > 0;
    startButton.disabled = !ready;
    errorText.textContent = '';
    summaryText.textContent = ready
      ? `${count} question${count === 1 ? '' : 's'} · ${config.difficulty === 'mixed' ? 'mixed difficulty' : DIFFICULTY_META[config.difficulty].label.toLowerCase()} · ${
          config.timerMode === TIMER_MODES.OFF ? 'untimed' : `${config.secondsPerQuestion}s per question`
        }`
      : 'Choose at least one topic to begin.';
  }

  refresh();

  root.append(h('section', { class: 'screen screen-setup' }, header, resumeSlot, statsStrip, form));
  return null;
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
