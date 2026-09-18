/**
 * Minimal DOM toolkit. All text goes through text nodes (never innerHTML), so
 * question content can safely contain markup-looking strings like "<div>".
 */

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Hyperscript element builder.
 *   h('button', { class: 'btn', onClick: fn, attrs: { 'aria-label': 'Next' } }, 'Next')
 * Props: class, text, dataset, attrs, style (object), on<Event> handlers, and any
 * other key is assigned as a DOM property (e.g. type, value, checked, disabled).
 */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  applyProps(el, props);
  append(el, children);
  return el;
}

/** Same as `h` but in the SVG namespace (attributes only). */
export function svg(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value !== undefined && value !== null && value !== false) el.setAttribute(name, String(value));
  }
  append(el, children);
  return el;
}

function applyProps(el, props) {
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value === undefined || value === null) continue;
    if (key === 'class') el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
    else if (key === 'text') el.textContent = value;
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'style') Object.assign(el.style, value);
    else if (key === 'attrs') {
      for (const [name, attr] of Object.entries(value)) {
        if (attr === false || attr === null || attr === undefined) continue;
        el.setAttribute(name, attr === true ? '' : String(attr));
      }
    } else if (key.startsWith('on') && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value);
    } else {
      el[key] = value;
    }
  }
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** Formats milliseconds as m:ss (or h:mm:ss). */
export function formatClock(ms) {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = String(totalSeconds % 60).padStart(2, '0');
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`;
}

/** Human-friendly duration: "45s", "3m 12s", "1h 4m". */
export function formatDuration(ms) {
  const totalSeconds = Math.round(Math.max(0, ms) / 1000);
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  if (minutes < 60) return `${minutes}m ${totalSeconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
export function formatDate(timestamp) {
  return dateFormatter.format(new Date(timestamp));
}

/** Moves keyboard/screen-reader focus to an element without scrolling jank. */
export function focusElement(el) {
  if (!el) return;
  if (!el.hasAttribute('tabindex') && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) {
    el.setAttribute('tabindex', '-1');
  }
  el.focus({ preventScroll: true });
}

export const prefersReducedMotion = () =>
  typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
