// #802 fill: {index: value} from the browser's profile, set the way a user would (value +
// input + change events) so the page's own scripts see it. Never touches a password field.
(function (fill) {
  var els = document.querySelectorAll('input, select, textarea'), n = 0;
  Object.keys(fill).forEach(function (k) {
    var e = els[+k];
    if (!e || e.type === 'password') return;
    var proto = e.tagName === 'SELECT' ? HTMLSelectElement.prototype : e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var set = Object.getOwnPropertyDescriptor(proto, 'value').set;
    set.call(e, fill[k]);
    e.dispatchEvent(new Event('input', { bubbles: true }));
    e.dispatchEvent(new Event('change', { bubbles: true }));
    n++;
  });
  return JSON.stringify({ filled: n });
})(__FILL__)
