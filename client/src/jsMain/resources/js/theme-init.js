// Applies the saved theme before first paint to avoid a light/dark flash.
// A classic (non-module) script loaded synchronously from <head>; kept out of
// the HTML so the Content-Security-Policy can forbid inline scripts.
(function () {
  try {
    var prefs = JSON.parse(localStorage.getItem('quizapp:prefs'));
    var theme = prefs && prefs.data && prefs.data.theme;
    if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  } catch (e) {
    /* storage unavailable: fall back to the OS setting */
  }
})();
