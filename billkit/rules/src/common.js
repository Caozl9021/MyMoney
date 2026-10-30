/* 麦米原创工具。仅解析完整字段，不从任意文本拼接数字。 */
var common = (function () {
  function text(v) { return typeof v === 'string' ? v.trim() : ''; }
  function json(v) {
    if (typeof v !== 'string' || v.length > 65536) return null;
    try { return JSON.parse(v); } catch (_) { return null; }
  }
  function moneyText(v) {
    return typeof v === 'number' && isFinite(v) ? String(v) : text(v);
  }
  function amount(v) {
    var s = moneyText(v);
    if (!/^(?:(?:0|[1-9][0-9]{0,6})|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\.[0-9]{1,2})?$/.test(s)) return null;
    s = s.replace(/,/g, '');
    var n = Number(s);
    return isFinite(n) && n > 0 && n <= 1000000 ? s : null;
  }
  function displayed(v) {
    var m = /^(?:人民币\s*|[¥￥]\s*)([0-9][0-9,.]*)$/.exec(text(v));
    return m ? amount(m[1]) : null;
  }
  function clock(v) {
    if (typeof v !== 'number' || !isFinite(v) || Math.floor(v) !== v || v <= 0) return 0;
    return v < 100000000000 ? v * 1000 : v;
  }
  function transactionTime(v) {
    if (typeof v === 'string') {
      if (!/^[1-9][0-9]{9,12}$/.test(v)) return null;
      v = Number(v);
    }
    if (typeof v !== 'number' || !isFinite(v) || Math.floor(v) !== v || v <= 0) return null;
    var t = clock(v);
    return t > 0 ? t : null;
  }
  function object(v) { return v && typeof v === 'object' && !Array.isArray(v) ? v : null; }
  function fields(v) {
    if (!Array.isArray(v) || v.length > 64) return null;
    var out = {}, items = v;
    for (var i = 0; i < items.length; i++) {
      var row = object(items[i]);
      if (!row) continue;
      var key = text(row.title).replace(/[：:]$/, '');
      if (!key || Object.prototype.hasOwnProperty.call(out, key)) return null;
      out[key] = text(row.content);
    }
    return out;
  }
  function result(type, money, observedAt, shop, method, transactionTime, orderId) {
    var t = clock(transactionTime);
    return { type: type, money: money, currency: 'CNY', shopName: text(shop),
      accountNameFrom: text(method), time: t, observedAt: clock(observedAt),
      hasExplicitTime: t > 0, orderId: text(orderId) };
  }
  function unsafe(s) { return /失败|取消|撤销|待支付|待付款|未支付|未付款|未扣款|待扣款|未完成|待完成|待确认|尚未|待到账|等待|受理中|支付中|付款中|处理中|进行中|验证码|冻结|过期|退还通知/.test(s); }
  return { text: text, moneyText: moneyText, json: json, object: object, amount: amount, displayed: displayed,
    clock: clock, transactionTime: transactionTime, fields: fields, result: result, unsafe: unsafe,
    BillType: { Expend: 'Expend', Income: 'Income', Refund: 'Refund' } };
})();
