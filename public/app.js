const byId = id => document.getElementById(id);
const format = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' });
const money = pennies => format.format(pennies / 100);
const decimal = pennies => (pennies / 100).toFixed(2);
const storagePrefix = 'one-cheque-live:';
let tableId = new URLSearchParams(location.search).get('table');
let token = tableId ? stored(tableId) : null;
let bill;
let editing;
let pendingItem;
let busy = false;
let polling = false;

function stored(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(storagePrefix + key);
    if (value === null) localStorage.removeItem(storagePrefix + key);
    else localStorage.setItem(storagePrefix + key, value);
  } catch {}
  return null;
}

function itemId() {
  // getRandomValues also works over HTTP when testing on a local network.
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

async function request(path, values) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {};
  const options = { headers, signal: AbortSignal.timeout(8000) };
  if (values) {
    options.method = 'POST';
    options.body = new URLSearchParams(values);
  }
  const response = await fetch('/api/tables' + path, options);
  const data = await response.json();
  if (!response.ok) {
    throw Object.assign(new Error(data.error), { status: response.status });
  }
  return data;
}

function status(message) {
  byId('status').textContent = message;
}

function showEntry() {
  byId('entry').hidden = false;
  byId('table').hidden = true;
  byId('entry-title').textContent = tableId ? 'Join the table' : 'Start a table';
  byId('entry-button').textContent = tableId ? 'Join table' : 'Create table';
  byId('start-another').hidden = !tableId;
  const previous = stored('last');
  byId('resume').hidden = !!tableId || !previous || !stored(previous);
  if (previous) byId('resume').href = '/?table=' + encodeURIComponent(previous);
}

function element(tag, label, cssClass) {
  const node = document.createElement(tag);
  node.textContent = label;
  if (cssClass) node.className = cssClass;
  return node;
}

function button(label, cssClass, handler) {
  const node = element('button', label, cssClass);
  node.type = 'button';
  node.addEventListener('click', handler);
  return node;
}

function orderRow(order, editable) {
  const row = element('div', '', 'order');
  row.append(element('span', order.name), element('span', money(order.price)));
  if (editable) {
    const controls = element('div', '', 'order-controls');
    controls.append(button('Edit', 'link', () => beginEdit(order)),
      button('Remove', 'remove', () => change('remove', { id: order.id })));
    row.append(controls);
  }
  return row;
}

function render(next) {
  if (bill && next.revision <= bill.revision) return;
  const previous = bill;
  bill = next;
  const mine = bill.diners.find(diner => diner.mine);
  const lastMine = previous?.diners.find(diner => diner.mine);
  byId('entry').hidden = true;
  byId('table').hidden = false;
  byId('your-name').textContent = 'Your orders · ' + mine.name;
  byId('your-total').textContent = money(mine.subtotal + mine.charge);
  if (JSON.stringify(lastMine?.orders) !== JSON.stringify(mine.orders) || previous?.closed !== bill.closed) {
    byId('your-orders').replaceChildren(...(mine.orders.length
      ? mine.orders.map(order => orderRow(order, !bill.closed))
      : [element('p', 'No items added yet.')]));
  }
  byId('item-form').hidden = bill.closed;

  byId('other-orders').replaceChildren(...bill.diners.filter(diner => !diner.mine).map(diner => {
    const section = element('section', '', 'diner');
    section.append(element('strong', diner.name),
      ...(diner.orders.length ? diner.orders.map(order => orderRow(order, false)) : [element('p', 'No items added yet.')]));
    return section;
  }));
  byId('breakdown').replaceChildren(...bill.diners.map(diner => {
    const row = element('div', '', 'summary-row');
    const name = element('span', diner.name + (diner.mine ? ' (you)' : ''));
    if (bill.service) name.append(element('small', money(diner.subtotal) + ' orders + ' + money(diner.charge) + ' service / tip'));
    row.append(name, element('strong', money(diner.subtotal + diner.charge)));
    return row;
  }));
  byId('service-form').hidden = !bill.host || bill.closed;
  if (previous?.service !== bill.service && document.activeElement !== byId('service')) {
    byId('service').value = decimal(bill.service);
  }
  byId('total').textContent = money(bill.total);
  byId('total-label').textContent = bill.closed ? 'Total to pay' : 'Running total';
  byId('finalise').hidden = !bill.host || bill.closed;
  byId('finalise').disabled = !bill.diners.some(diner => diner.orders.length);
  byId('server-button').hidden = !bill.closed;
  byId('server-total').textContent = money(bill.total);
  byId('invite').hidden = bill.closed;
  byId('link').value = new URL('/?table=' + encodeURIComponent(tableId), location.origin).href;
}

async function refresh() {
  if (!token || busy || polling || bill?.closed) return;
  polling = true;
  try {
    const firstLoad = !bill;
    render(await request('/' + encodeURIComponent(tableId)));
    if (firstLoad) status('');
    byId('connection').textContent = '';
  } catch (error) {
    if (error.status === 401 || error.status === 404) {
      stored(tableId, null);
      token = null;
      bill = undefined;
      showEntry();
      status(error.message);
    } else {
      if (!bill) {
        showEntry();
        status('Could not reach the table. Reconnecting…');
      }
      byId('connection').textContent = 'Reconnecting…';
    }
  } finally {
    polling = false;
  }
}

async function change(action, values, onSuccess, errorTarget = status) {
  if (!token || busy) return;
  busy = true;
  try {
    render(await request('/' + encodeURIComponent(tableId) + '/' + action, values));
    status('');
    onSuccess?.();
  } catch (error) {
    errorTarget(error.message || 'Could not reach the table. Try again.');
  } finally {
    busy = false;
    void refresh();
  }
}

byId('entry-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || token) return;
  busy = true;
  try {
    const result = await request(tableId ? '/' + encodeURIComponent(tableId) + '/join' : '', new FormData(event.currentTarget));
    tableId = result.table;
    token = result.token;
    stored(tableId, token);
    stored('last', tableId);
    history.replaceState(null, '', '/?table=' + encodeURIComponent(tableId));
    status('');
  } catch (error) {
    status(error.message || 'Could not join the table. Try again.');
  } finally {
    busy = false;
    void refresh();
  }
});

byId('item-form').addEventListener('submit', event => {
  event.preventDefault();
  if (busy) return;
  const form = event.currentTarget;
  const fields = Object.fromEntries(new FormData(form));
  if (!pendingItem || fields.name !== pendingItem.name || fields.price !== pendingItem.price) {
    pendingItem = { ...fields, id: itemId() };
  }
  change('add', pendingItem, () => {
    if (form.elements.namedItem('name').value === fields.name && form.elements.price.value === fields.price) form.reset();
    pendingItem = undefined;
  });
});

function beginEdit(order) {
  editing = order.id;
  const form = byId('edit-form');
  form.elements.namedItem('name').value = order.name;
  form.elements.price.value = decimal(order.price);
  byId('edit-error').textContent = '';
  byId('edit-dialog').showModal();
}

byId('edit-form').addEventListener('submit', event => {
  event.preventDefault();
  const fields = Object.fromEntries(new FormData(event.currentTarget));
  change('edit', { ...fields, id: editing }, () => byId('edit-dialog').close(),
    message => { byId('edit-error').textContent = message; });
});
byId('cancel-edit').addEventListener('click', () => byId('edit-dialog').close());

byId('service-form').addEventListener('submit', event => {
  event.preventDefault();
  change('service', new FormData(event.currentTarget));
});

byId('finalise').addEventListener('click', () => {
  const reviewed = bill;
  if (confirm('Finalise the bill at ' + money(reviewed.total) + '? This locks everyone’s orders.')) {
    change('close', { reviewed: reviewed.revision }, () => byId('server-view').showModal());
  }
});
byId('server-button').addEventListener('click', () => byId('server-view').showModal());

async function copy(text, confirmation) {
  try {
    await navigator.clipboard.writeText(text);
    status(confirmation);
  } catch {
    byId('copy-text').value = text;
    byId('copy-dialog').showModal();
    byId('copy-text').select();
  }
}

byId('copy-link').addEventListener('click', () => copy(byId('link').value, 'Invite link copied.'));
byId('copy-summary').addEventListener('click', () => {
  const lines = bill.diners.flatMap(diner => [
    diner.name + ' — ' + money(diner.subtotal + diner.charge),
    ...diner.orders.map(order => '  ' + order.name + ' — ' + money(order.price)),
    ...(bill.service ? ['  Service / tip — ' + money(diner.charge)] : [])
  ]);
  copy(['One Cheque', ...lines, (bill.closed ? 'Total to pay — ' : 'Running total — ') + money(bill.total)].join('\n'), 'Breakdown copied.');
});

if (token) void refresh();
else showEntry();
setInterval(refresh, 2500);
