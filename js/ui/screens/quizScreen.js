/**
 * Active quiz screen: question card, option selection with instant feedback,
 * live timer(s), running score, keyboard shortcuts and auto-save.
 *
 * Timing rules
 *  - Per-question mode: when the countdown hits zero the question is recorded as
 *    "timed out" (0 points) and the correct answer is revealed.
 *  - Whole-quiz mode: when the countdown hits zero the quiz is auto-submitted;
 *    the current and remaining questions count as timed out / skipped.
 *  - Timers pause while feedback is shown (the question is already locked), so
 *    reading explanations never costs time.
 */

import { TIMER_MODES } from '../../core/config.js';
import {
  END_REASONS,
  ITEM_STATUS,
  answerCurrent,
  getCurrentItem,
  getLiveStats,
  getQuestionTimeLimitMs,
  getSessionTimeLimitMs,
  goToNext,
  isItemAnswered,
  isLastQuestion,
  timeoutCurrent,
  withClock,
} from '../../core/quizEngine.js';
import { Timer } from '../../core/timer.js';
import { chip, difficultyChip, timerRing } from '../components.js';
import { confirmDialog } from '../dialog.js';
import { focusElement, formatClock, h } from '../dom.js';

const OPTION_KEYS = ['a', 'b', 'c', 'd', 'e', 'f'];
const LOW_TIME_ANNOUNCE_MS = 5000;

export function renderQuizScreen(root, ctx, { session: initialSession }) {
  const { bank, storage } = ctx;
  let session = initialSession;
  let completed = false;
  let mounted = true; // false once navigated away; deferred timer callbacks check it
  let lowTimeAnnounced = false;

  const { timerMode } = session.config;
  const questionLimitMs = getQuestionTimeLimitMs(session.config);
  const sessionLimitMs = getSessionTimeLimitMs(session.config, session.items.length);

  /* ---------- static layout ---------- */

  const progressText = h('span', { class: 'quiz-progress-text' });
  const progressFill = h('span', { class: 'progress-fill' });
  const progressBar = h(
    'div',
    { class: 'progress', attrs: { role: 'progressbar', 'aria-label': 'Quiz progress', 'aria-valuemin': '0', 'aria-valuemax': String(session.items.length) } },
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

  const screen = h(
    'section',
    { class: 'screen screen-quiz' },
    h(
      'div',
      { class: 'quiz-topbar' },
      h('div', { class: 'quiz-meta' }, progressText, h('span', { class: 'quiz-score' }, pointsValue, ' pts'), streakBadge, elapsedText),
      ring?.el ?? null,
    ),
    progressBar,
    h(
      'article',
      { class: 'card question-card' },
      h('div', { class: 'question-chips' }, topicChipSlot),
      questionText,
      codeBlock,
      optionList,
      feedback,
    ),
    h(
      'div',
      { class: 'quiz-actions' },
      endButton,
      h('p', { class: 'kbd-hint' }, 'Keys: ', h('kbd', {}, '1'), '–', h('kbd', {}, '4'), ' answer · ', h('kbd', {}, '→'), ' next'),
      nextButton,
    ),
  );
  root.append(screen);

  /* ---------- timers ---------- */

  const sessionTimer = new Timer({
    limitMs: sessionLimitMs,
    elapsedMs: session.clock.sessionElapsedMs,
    onTick: (state) => {
      if (timerMode === TIMER_MODES.SESSION) {
        ring.update(state);
        maybeAnnounceLowTime(state.remainingMs);
      } else if (timerMode === TIMER_MODES.OFF) {
        elapsedText.textContent = `⏱ ${formatClock(state.elapsedMs)}`;
      }
    },
    // Deferred: expiry can fire synchronously inside start() during a render,
    // and finishing navigates away — never do that mid-render.
    onExpire: () => queueMicrotask(handleSessionTimeUp),
  });

  let questionTimer = null;

  function createQuestionTimer(elapsedMs) {
    questionTimer?.dispose();
    questionTimer = new Timer({
      limitMs: questionLimitMs,
      elapsedMs,
      onTick: (state) => {
        if (timerMode === TIMER_MODES.QUESTION) {
          ring.update(state);
          maybeAnnounceLowTime(state.remainingMs);
        }
      },
      onExpire: () => queueMicrotask(handleQuestionTimeUp),
    });
  }

  function maybeAnnounceLowTime(remainingMs) {
    if (!lowTimeAnnounced && remainingMs <= LOW_TIME_ANNOUNCE_MS && remainingMs > 0) {
      lowTimeAnnounced = true;
      ctx.announce('5 seconds left');
    }
  }

  function pauseTimers() {
    questionTimer?.pause();
    sessionTimer.pause();
  }

  function snapshotClock() {
    return {
      questionElapsedMs: questionTimer?.elapsed() ?? session.clock.questionElapsedMs,
      sessionElapsedMs: sessionTimer.elapsed(),
    };
  }

  function persist() {
    if (completed) return;
    session = withClock(session, snapshotClock());
    storage.saveActiveSession(session);
  }

  /* ---------- rendering ---------- */

  function currentQuestion() {
    return bank.getQuestion(getCurrentItem(session).questionId);
  }

  function renderHeader() {
    const total = session.items.length;
    const stats = getLiveStats(session);
    progressText.textContent = `Question ${session.currentIndex + 1} of ${total}`;
    progressFill.style.width = `${(stats.answered / total) * 100}%`;
    progressBar.setAttribute('aria-valuenow', String(stats.answered));
    progressBar.setAttribute('aria-valuetext', `${stats.answered} of ${total} answered`);
    pointsValue.textContent = String(stats.points);
    streakBadge.hidden = stats.streak < 2;
    streakBadge.textContent = `🔥 ${stats.streak} in a row`;
  }

  function renderQuestion() {
    const item = getCurrentItem(session);
    const question = currentQuestion();

    if (!question) {
      // The bank changed since this quiz was saved: skip the orphaned question.
      session = timeoutCurrent(session);
      advance();
      return;
    }

    // The whole-quiz countdown should only warn once, not once per question.
    if (timerMode === TIMER_MODES.QUESTION) lowTimeAnnounced = false;
    renderHeader();

    const topic = bank.getTopic(question.topic);
    topicChipSlot.replaceChildren(chip(`${topic?.icon ?? ''} ${topic?.name ?? question.topic}`.trim()), difficultyChip(question.difficulty));
    questionText.textContent = question.question;
    codeBlock.hidden = !question.code;
    codeBlock.firstChild.textContent = question.code ?? '';

    optionList.replaceChildren(
      ...item.optionOrder.map((originalIndex, displayIndex) =>
        h(
          'li',
          {},
          h(
            'button',
            {
              type: 'button',
              class: 'option',
              dataset: { display: String(displayIndex), original: String(originalIndex) },
              onClick: () => select(displayIndex),
            },
            h('span', { class: 'option-key', attrs: { 'aria-hidden': 'true' } }, OPTION_KEYS[displayIndex].toUpperCase()),
            h('span', { class: 'option-text' }, question.options[originalIndex]),
            h('span', { class: 'option-mark' }),
          ),
        ),
      ),
    );
    feedback.replaceChildren();
    feedback.className = 'feedback';
    nextButton.hidden = true;
    nextButton.textContent = isLastQuestion(session) ? 'See results →' : 'Next question →';

    createQuestionTimer(session.clock.questionElapsedMs);

    if (isItemAnswered(item)) {
      // Resumed after answering but before moving on.
      questionTimer.pause();
      if (ring && timerMode === TIMER_MODES.QUESTION) ring.update(questionTimer.state());
      if (ring && timerMode === TIMER_MODES.SESSION) ring.update(sessionTimer.state());
      if (timerMode === TIMER_MODES.OFF) sessionTimer.tick();
      showFeedback();
      return;
    }

    sessionTimer.start();
    questionTimer.start();
    focusElement(questionText);
  }

  function showFeedback() {
    const item = getCurrentItem(session);
    const question = currentQuestion();

    for (const button of optionList.querySelectorAll('.option')) {
      const original = Number(button.dataset.original);
      const isCorrect = original === question.answer;
      const isSelected = original === item.selected;
      button.disabled = true;
      button.classList.toggle('is-correct', isCorrect);
      button.classList.toggle('is-wrong', isSelected && !isCorrect);
      button.classList.toggle('is-selected', isSelected);
      const mark = button.querySelector('.option-mark');
      mark.textContent = isCorrect ? '✓ Correct answer' : isSelected ? '✗ Your answer' : '';
    }

    const correctText = question.options[question.answer];
    const messages = {
      [ITEM_STATUS.CORRECT]: { icon: '✓', title: 'Correct!', tone: 'good' },
      [ITEM_STATUS.WRONG]: { icon: '✗', title: 'Not quite', tone: 'critical' },
      [ITEM_STATUS.TIMEOUT]: { icon: '⏱', title: "Time's up!", tone: 'warning' },
    };
    const message = messages[item.status];
    const pointsText = item.points > 0 ? `+${item.points} pts` : item.points < 0 ? `${item.points} pts` : '0 pts';

    feedback.className = `feedback tone-${message.tone}`;
    feedback.replaceChildren(
      h(
        'p',
        { class: 'feedback-title' },
        h('span', { class: 'feedback-icon', attrs: { 'aria-hidden': 'true' } }, message.icon),
        message.title,
        h('span', { class: 'feedback-points' }, pointsText),
      ),
      item.status === ITEM_STATUS.CORRECT ? null : h('p', { class: 'feedback-answer' }, 'Correct answer: ', h('strong', {}, correctText)),
      h('p', { class: 'feedback-explanation' }, question.explanation),
    );

    renderHeader();
    nextButton.hidden = false;
    focusElement(nextButton); // the aria-live feedback panel announces the result
  }

  /* ---------- actions ---------- */

  function select(displayIndex) {
    if (completed) return;
    const item = getCurrentItem(session);
    if (!item || isItemAnswered(item) || displayIndex >= item.optionOrder.length) return;

    pauseTimers();
    const clock = snapshotClock();
    const { session: answered } = answerCurrent(session, bank, item.optionOrder[displayIndex], {
      timeSpentMs: clock.questionElapsedMs,
      remainingFraction: questionTimer.fraction(),
    });
    session = withClock(answered, clock);
    persist();
    showFeedback();
  }

  function handleQuestionTimeUp() {
    if (!mounted || completed || isItemAnswered(getCurrentItem(session))) return;
    sessionTimer.pause();
    session = withClock(timeoutCurrent(session, { timeSpentMs: questionLimitMs }), snapshotClock());
    persist();
    showFeedback();
  }

  function handleSessionTimeUp() {
    if (!mounted || completed) return;
    questionTimer?.pause();
    let final = session;
    if (!isItemAnswered(getCurrentItem(final))) {
      final = timeoutCurrent(final, { timeSpentMs: questionTimer?.elapsed() ?? 0 });
    }
    finish(withClock(final, snapshotClock()), END_REASONS.TIME_UP);
    ctx.toast("Time's up! Your quiz was submitted automatically.", { tone: 'warning' });
  }

  function next() {
    if (completed || !isItemAnswered(getCurrentItem(session))) return;
    advance();
  }

  function advance() {
    if (isLastQuestion(session)) {
      finish(session, END_REASONS.COMPLETED);
      return;
    }
    session = goToNext(session, { now: ctx.now() });
    persist();
    renderQuestion();
  }

  async function endQuizEarly() {
    if (completed) return;
    const ok = await confirmDialog({
      title: 'End the quiz now?',
      message: 'Unanswered questions will be marked as skipped and the attempt will be saved to your history.',
      confirmText: 'End quiz',
      danger: true,
    });
    if (!ok || completed) return;
    pauseTimers();
    finish(withClock(session, snapshotClock()), END_REASONS.QUIT);
  }

  function finish(finalSession, reason) {
    completed = true;
    questionTimer?.dispose();
    sessionTimer.dispose();
    ctx.completeQuiz(finalSession, reason);
  }

  /* ---------- global listeners ---------- */

  function onKeyDown(event) {
    if (completed || event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target.closest?.('input, textarea, select, dialog')) return;
    const item = getCurrentItem(session);
    const key = event.key.toLowerCase();

    if (!isItemAnswered(item)) {
      const digit = Number.parseInt(key, 10);
      const index = Number.isInteger(digit) ? digit - 1 : OPTION_KEYS.indexOf(key);
      if (index >= 0 && index < item.optionOrder.length) {
        event.preventDefault();
        select(index);
      }
    } else if (key === 'arrowright' || key === 'n') {
      event.preventDefault();
      next();
    }
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'hidden') persist();
  }

  document.addEventListener('keydown', onKeyDown);
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('pagehide', persist);

  renderQuestion();

  /* ---------- cleanup (navigating away pauses and saves) ---------- */

  return () => {
    mounted = false;
    document.removeEventListener('keydown', onKeyDown);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    window.removeEventListener('pagehide', persist);
    // Close a confirm dialog this screen may have left open.
    for (const cancel of document.querySelectorAll('dialog.dialog [data-action="cancel"]')) cancel.click();
    if (!completed) {
      pauseTimers();
      persist();
      questionTimer?.dispose();
      sessionTimer.dispose();
    }
  };
}
