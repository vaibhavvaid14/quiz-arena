/**
 * History records and cross-attempt analytics.
 */

/**
 * Snapshot of a finished session for the history log. The summary is stored
 * (not recomputed) so the history list stays correct even if the question bank
 * changes later; `items` is kept so the full review can be reopened.
 */
export function buildHistoryEntry(session, summary) {
  return {
    id: session.id,
    finishedAt: session.finishedAt ?? Date.now(),
    playerName: session.config.playerName,
    config: session.config,
    endReason: session.endReason,
    items: session.items,
    summary: {
      total: summary.total,
      correct: summary.correct,
      wrong: summary.wrong,
      timedOut: summary.timedOut,
      skipped: summary.skipped,
      percentage: summary.percentage,
      points: summary.points,
      maxPoints: summary.maxPoints,
      grade: summary.grade.grade,
      durationMs: summary.durationMs,
      bestStreak: summary.bestStreak,
      byTopic: summary.byTopic.map(({ key, correct, total }) => ({ key, correct, total })),
    },
  };
}

/** Rebuilds a finished-session object from a history entry (for re-opening its review). */
export function sessionFromHistoryEntry(entry) {
  return {
    id: entry.id,
    status: 'finished',
    endReason: entry.endReason ?? 'completed',
    finishedAt: entry.finishedAt,
    config: entry.config,
    currentIndex: entry.items.length - 1,
    clock: { questionElapsedMs: 0, sessionElapsedMs: 0 },
    items: entry.items,
  };
}

/**
 * Aggregates all attempts: totals, averages and per-topic mastery.
 * @param {object[]} history  newest first
 * @param {{ id: string, name: string }[]} topics  used for ordering and names
 */
export function aggregateHistory(history, topics = []) {
  const topicTotals = new Map();
  let questions = 0;
  let correct = 0;
  let percentageSum = 0;
  let best = null;

  for (const entry of history) {
    const s = entry.summary;
    questions += s.total;
    correct += s.correct;
    percentageSum += s.percentage;
    if (!best || s.percentage > best.summary.percentage) best = entry;
    for (const t of s.byTopic ?? []) {
      const acc = topicTotals.get(t.key) ?? { correct: 0, total: 0, attempts: 0 };
      acc.correct += t.correct;
      acc.total += t.total;
      acc.attempts += 1;
      topicTotals.set(t.key, acc);
    }
  }

  const topicNames = new Map(topics.map((t) => [t.id, t.name]));
  const topicOrder = new Map(topics.map((t, i) => [t.id, i]));
  const byTopic = [...topicTotals.entries()]
    .map(([key, acc]) => ({
      key,
      label: topicNames.get(key) ?? key,
      ...acc,
      percentage: acc.total === 0 ? 0 : Math.round((acc.correct / acc.total) * 100),
    }))
    .sort((a, b) => (topicOrder.get(a.key) ?? 99) - (topicOrder.get(b.key) ?? 99));

  return {
    attempts: history.length,
    questions,
    correct,
    averagePercentage: history.length === 0 ? 0 : Math.round(percentageSum / history.length),
    bestPercentage: best ? best.summary.percentage : 0,
    overallAccuracy: questions === 0 ? 0 : Math.round((correct / questions) * 100),
    byTopic,
  };
}
