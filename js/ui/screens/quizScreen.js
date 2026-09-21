/**
 * Active quiz screen, driven entirely by server state.
 *
 * The browser never knows the correct answer in advance: it shows the options,
 * sends the choice, and renders whatever the server returns (correct option,
 * explanation, points). Every action (answer / time-up / next / end) is one API
 * call that returns the complete new state.
 *
 * The local Timer only drives the on-screen countdown; the server measures time
 * with its own clock and has the final say.
 *  - Per-question mode: at zero we report a time-out; the correct answer is revealed.
 *  - Whole-quiz mode: at zero we ask the server to finish the quiz.
 *  - Timers stop while feedback is shown (the server doesn't charge that time either).
 */

import { ITEM_STATUS, TIMER_MODES } from '../../core/config.js';
import { Timer } from '../../core/timer.js';
import { chip, difficultyChip, timerRing } from '../components.js';
import { confirmDialog } from '../dialog.js';
import { focusElement, formatClock, h, richText } from '../dom.js';

const OPTION_KEYS = ['a', 'b', 'c', 'd', 'e', 'f'];
const LOW_TIME_ANNOUNCE_MS = 5000;

const FEEDBACK = {
  [ITEM_STATUS.CORRECT]: { icon: '✓', title: 'Correct!', tone: 'good' },
  [ITEM_STATUS.WRONG]: { icon: '✗', title: 'Not quite', tone: 'critical' },
  [ITEM_STATUS.TIMEOUT]: { icon: '⏱', title: "Time's up!", tone: 'warning' },
};

export function renderQuizScreen(root, ctx, { state: initialState }) {
  const { api } = ctx;
  let state = initialState;
  let mounted = true;
  let busy = false; // one request at a time
  let lowTimeAnnounced = false;

  const { timerMode } = state.config;
  let questionTimer = null;
  let sessionTimer = null;

  /* ---------- static layout ---------- */

  const progressText = h('span', { class: 'quiz-progress-text' });
  const progressFill = h('span', { class: 'progress-fill' });
  const progressBar = h(
    'div',
    { class: 'progress', attrs: { role: 'progressbar', 'aria-label': 'Quiz progress', 'aria-valuemin': '0', 'aria-valuemax': String(state.total) } },
    progressFill,
  );
  const pointsValue = h('strong', {}, '0');
  const streakBadge = h('span', { class: 'streak', hidden: true });
  const elapsedText = h('span', { class: 'elapsed' });

  const ring =
    timerMode === TIMER_MODES.OFF
      ? null
      : timerRing({ label: timerMode === TIMER_MODES.QUESTION ? 'Time left for this question' : 'Time left for the quiz' });

  const topicChipSlot = h('span', { class: 'chip-slot' });
  const questionText = h('h1', { class: 'question-text', attrs: { tabindex: '-1' } });
  const codeBlock = h('pre', { class: 'code-block', hidden: true }, h('code'));
  const optionList = h('ol', { class: 'options', attrs: { 'aria-label': 'Answer options' } });
  const feedback = h('div', { class: 'feedback', attrs: { 'aria-live': 'polite' } });

  const endButton = h('button', { type: 'button', class: 'btn btn-ghost', onClick: () => endQuizEarly() }, 'End quiz');
  const nextButton = h('button', { type: 'button', class: 'btn btn-primary', hidden: true, dataset: { action: 'next' }, onClick: () => next() });

  root.append(
    h(
      'section',
      { class: 'screen screen-quiz' },
      h(
        'div',
        { class: 'quiz-topbar' },
        h('div', { class: 'quiz-meta' }, progressText, h('span', { class: 'quiz-score' }, pointsValue, ' pts'), streakBadge, elapsedText),
        ring?.el ?? null,
      ),
      progressBar,
      h('article', { class: 'card question-card' }, h('div', { class: 'question-chips' }, topicChipSlot), questionText, codeBlock, optionList, feedback),
      h(
        'div',
        { class: 'quiz-actions' },
        endButton,
        h('p', { class: 'kbd-hint' }, 'Keys: ', h('kbd', {}, '1'), '–', h('kbd', {}, '4'), ' answer · ', h('kbd', {}, '→'), ' next'),
        nextButton,
      ),
    ),
  );

  /* ---------- timers (display only; the server keeps the real time) ---------- */

  function stopTimers() {
    questionTimer?.dispose();
    sessionTimer?.dispose();
    questionTimer = null;
    sessionTimer = null;
  }

  function startTimers() {
    stopTimers();
    const { clock } = state;
    if (timerMode === TIMER_MODES.QUESTION) {
      questionTimer = new Timer({
        limitMs: clock.questionLimitMs,
        elapsedMs: clock.questionElapsedMs,
        onTick: (s) => {
          ring.update(s);
          maybeAnnounceLowTime(s.remainingMs);
        },
        // Deferred: start() ticks synchronously and may expire during render.
        onExpire: () => queueMicrotask(() => reportTimeout()),
      }).start();
    } else if (timerMode === TIMER_MODES.SESSION) {
      sessionTimer = new Timer({
        limitMs: clock.sessionLimitMs,
        elapsedMs: clock.sessionElapsedMs,
        onTick: (s) => {
          ring.update(s);
          maybeAnnounceLowTime(s.remainingMs);
        },
        onExpire: () => queueMicrotask(() => finishQuiz()),
      }).start();
    } else {
      sessionTimer = new Timer({
        elapsedMs: clock.sessionElapsedMs,
        onTick: (s) => {
          elapsedText.textContent = `⏱ ${formatClock(s.elapsedMs)}`;
        },
      }).start();
    }
  }

  /** Shows the frozen clock while feedback is on screen. */
  function showStoppedClock() {
    const { clock } = state;
    if (timerMode === TIMER_MODES.QUESTION) {
      const remaining = Math.max(0, clock.questionLimitMs - clock.questionElapsedMs);
      ring.update({ remainingMs: remaining, fraction: remaining / clock.questionLimitMs });
    } else if (timerMode === TIMER_MODES.SESSION) {
      const remaining = Math.max(0, clock.sessionLimitMs - clock.sessionElapsedMs);
      ring.update({ remainingMs: remaining, fraction: remaining / clock.sessionLimitMs });
    } else {
      elapsedText.textContent = `⏱ ${formatClock(clock.sessionElapsedMs)}`;
    }
  }

  function maybeAnnounceLowTime(remainingMs) {
    if (!lowTimeAnnounced && remainingMs <= LOW_TIME_ANNOUNCE_MS && remainingMs > 0) {
      lowTimeAnnounced = true;
      ctx.announce('5 seconds left');
    }
  }

  /* ---------- rendering ---------- */

  let renderedPosition = null;

  function render() {
    if (state.status === 'finished') {
      finishLocally();
      return;
    }
    const { current, live, total } = state;

    progressText.textContent = `Question ${current.position + 1} of ${total}`;
    progressFill.style.width = `${(live.answered / total) * 100}%`;
    progressBar.setAttribute('aria-valuenow', String(live.answered));
    progressBar.setAttribute('aria-valuetext', `${live.answered} of ${total} answered`);
    pointsValue.textContent = String(live.points);
    streakBadge.hidden = live.streak < 2;
    streakBadge.textContent = `🔥 ${live.streak} in a row`;

    if (renderedPosition !== current.position) {
      renderedPosition = current.position;
      if (timerMode === TIMER_MODES.QUESTION) lowTimeAnnounced = false;
      renderQuestion(current);
    }

    if (current.status === ITEM_STATUS.PENDING) {
      setOptionsLocked(false);
      startTimers();
    } else {
      stopTimers();
      showStoppedClock();
      showFeedback(current);
    }
  }

  function renderQuestion(current) {
    const { question } = current;
    const topic = ctx.getTopic(question.topic);
    topicChipSlot.replaceChildren(chip(`${topic?.icon ?? ''} ${topic?.name ?? question.topic}`.trim()), difficultyChip(question.difficulty));
    questionText.replaceChildren(...richText(question.prompt));
    codeBlock.hidden = !question.code;
    codeBlock.firstChild.textContent = question.code ?? '';

    optionList.replaceChildren(
      ...question.options.map((option, index) =>
        h(
          'li',
          {},
          h(
            'button',
            { type: 'button', class: 'option', dataset: { optionId: String(option.id), index: String(index) }, onClick: () => select(option.id) },
            h('span', { class: 'option-key', attrs: { 'aria-hidden': 'true' } }, OPTION_KEYS[index].toUpperCase()),
            h('span', { class: 'option-text' }, option.text),
            h('span', { class: 'option-mark' }),
          ),
        ),
      ),
    );
    feedback.replaceChildren();
    feedback.className = 'feedback';
    nextButton.hidden = true;
    nextButton.textContent = current.isLast ? 'See results →' : 'Next question →';
    focusElement(questionText);
  }

  function setOptionsLocked(locked) {
    for (const button of optionList.querySelectorAll('.option')) button.disabled = locked;
  }

  function showFeedback(current) {
    for (const button of optionList.querySelectorAll('.option')) {
      const id = Number(button.dataset.optionId);
      const isCorrect = id === current.correctOptionId;
      const isSelected = id === current.selectedOptionId;
      button.disabled = true;
      button.classList.remove('is-pending');
      button.classList.toggle('is-correct', isCorrect);
      button.classList.toggle('is-wrong', isSelected && !isCorrect);
      button.classList.toggle('is-selected', isSelected);
      button.querySelector('.option-mark').textContent = isCorrect ? '✓ Correct answer' : isSelected ? '✗ Your answer' : '';
    }

    const message = FEEDBACK[current.status];
    const correctText = current.question.options.find((o) => o.id === current.correctOptionId)?.text ?? '';
    const pointsText = current.points > 0 ? `+${current.points} pts` : `${current.points} pts`;

    feedback.className = `feedback tone-${message.tone}`;
    // Built with h() so the conditional line can be null (replaceChildren would print "null").
    feedback.replaceChildren(
      ...h(
        'div',
        {},
        h(
          'p',
          { class: 'feedback-title' },
          h('span', { class: 'feedback-icon', attrs: { 'aria-hidden': 'true' } }, message.icon),
          message.title,
          h('span', { class: 'feedback-points' }, pointsText),
        ),
        current.status === ITEM_STATUS.CORRECT ? null : h('p', { class: 'feedback-answer' }, 'Correct answer: ', h('strong', {}, correctText)),
        h('p', { class: 'feedback-explanation' }, ...richText(current.explanation)),
      ).childNodes,
    );

    nextButton.hidden = false;
    focusElement(nextButton); // the aria-live feedback panel announces the result
  }

  /* ---------- server round-trips ---------- */

  /**
   * Runs one API call with the screen locked, then renders the returned state.
   * On failure: a stale-tab conflict reloads the real state; anything else
   * restores the screen so the player can try again.
   */
  async function act(call, { onFailure } = {}) {
    if (busy || !mounted) return;
    busy = true;
    try {
      const next = await call();
      if (!mounted) return;
      state = next;
      render();
    } catch (error) {
      if (!mounted) return;
      if (error.status === 409) {
        try {
          state = await api.getAttempt(state.id);
          render();
          return;
        } catch (reloadError) {
          ctx.handleError(reloadError);
          return;
        }
      }
      ctx.handleError(error);
      onFailure?.();
    } finally {
      busy = false;
    }
  }

  function select(optionId) {
    if (busy || state.current?.status !== ITEM_STATUS.PENDING) return;
    stopTimers();
    setOptionsLocked(true);
    optionList.querySelector(`[data-option-id="${optionId}"]`)?.classList.add('is-pending');
    act(() => api.answer(state.id, state.current.position, optionId), {
      onFailure: () => {
        optionList.querySelector('.is-pending')?.classList.remove('is-pending');
        render(); // unlocks the options and restarts the countdown (the server's clock still decides)
      },
    });
  }

  function reportTimeout() {
    if (!mounted || state.current?.status !== ITEM_STATUS.PENDING) return;
    setOptionsLocked(true);
    act(() => api.answer(state.id, state.current.position, null), { onFailure: () => setOptionsLocked(false) });
  }

  function next() {
    if (state.current?.status === ITEM_STATUS.PENDING) return;
    act(() => api.next(state.id, state.current.position));
  }

  function finishQuiz() {
    stopTimers();
    act(() => api.finish(state.id), { onFailure: () => render() });
  }

  async function endQuizEarly() {
    if (busy) return;
    const ok = await confirmDialog({
      title: 'End the quiz now?',
      message: 'Unanswered questions will be marked as skipped and the attempt will be saved to your history.',
      confirmText: 'End quiz',
      danger: true,
    });
    if (ok && mounted) finishQuiz();
  }

  function finishLocally() {
    if (!mounted) return;
    stopTimers();
    if (state.endReason === 'time-up') ctx.toast("Time's up! Your quiz was submitted automatically.", { tone: 'warning' });
    ctx.completeQuiz(state);
  }

  /* ---------- keyboard ---------- */

  function onKeyDown(event) {
    if (busy || event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target.closest?.('input, textarea, select, dialog')) return;
    const current = state.current;
    if (!current) return;
    const key = event.key.toLowerCase();

    if (current.status === ITEM_STATUS.PENDING) {
      const digit = Number.parseInt(key, 10);
      const index = Number.isInteger(digit) ? digit - 1 : OPTION_KEYS.indexOf(key);
      const option = current.question.options[index];
      if (index >= 0 && option) {
        event.preventDefault();
        select(option.id);
      }
    } else if (key === 'arrowright' || key === 'n') {
      event.preventDefault();
      next();
    }
  }

  document.addEventListener('keydown', onKeyDown);
  render();

  return () => {
    mounted = false;
    stopTimers();
    document.removeEventListener('keydown', onKeyDown);
    // Close a confirm dialog this screen may have left open.
    for (const cancel of document.querySelectorAll('dialog.dialog [data-action="cancel"]')) cancel.click();
  };
}
