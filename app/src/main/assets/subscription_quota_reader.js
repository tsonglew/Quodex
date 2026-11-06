async function (provider) {
    // Runs only in the provider's own page. Login secrets never leave this browser.
    const origins = { KIMI: 'https://www.kimi.com', WORKBUDDY: 'https://www.workbuddy.ai', GROK: 'https://grok.com' };
    if (location.origin !== origins[provider]) return { kind: 'origin' };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    const limit = 131072;
    function pick(value, keys) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return value;
        const safe = {};
        for (const key of keys) if (key in value) safe[key] = value[key];
        return safe;
    }
    async function read(path, body, extraHeaders) {
        const headers = Object.assign({ Accept: 'application/json' }, extraHeaders || {});
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        const response = await fetch(path, {
            method: body === undefined ? 'GET' : 'POST', headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal: controller.signal
        });
        if (!response.ok) {
            const retry = response.headers.get('Retry-After');
            let seconds = Number(retry);
            if (!retry || !Number.isFinite(seconds)) seconds = (Date.parse(retry) - Date.now()) / 1000;
            throw { status: response.status, retrySeconds: Number.isFinite(seconds) ? Math.max(0, Math.ceil(seconds)) : 60 };
        }
        if (!(response.headers.get('Content-Type') || '').toLowerCase().includes('json')) throw { status: 403 };
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let text = '', size = 0;
        while (true) {
            const part = await reader.read();
            if (part.done) break;
            size += part.value.byteLength;
            if (size > limit) { await reader.cancel(); throw { status: 422 }; }
            text += decoder.decode(part.value, { stream: true });
        }
        text += decoder.decode();
        return JSON.parse(text);
    }
    try {
        let result;
        if (provider === 'KIMI') {
            const token = localStorage.getItem('access_token');
            if (!token) return { kind: 'login' };
            const root = await read('/apiv2/kimi.gateway.membership.v2.MembershipService/GetSubscriptionStats', {}, {
                Authorization: 'Bearer ' + token, 'connect-protocol-version': '1'
            });
            result = { subscriptionBalance: pick(root.subscriptionBalance, ['unit', 'amountUsedRatio', 'expireTime']) };
            if ('boosterWallets' in root) {
                if (!Array.isArray(root.boosterWallets) || root.boosterWallets.length > 8) throw { status: 422 };
                result.boosterWallets = root.boosterWallets.map(wallet => ({ status: wallet.status,
                    moneyLeft: pick(wallet.moneyLeft, ['currency', 'priceInCents']) }));
            }
        } else if (provider === 'WORKBUDDY') {
            const rows = [], ids = new Set();
            let expected = null;
            for (let page = 1; page <= 20; page++) {
                const root = await read('/billing/meter/get-user-resource', {
                    PageNumber: page, PageSize: 200, ProductCode: 'p_tcaca', Status: [0, 3], OnlyValidPeriod: true
                }, { 'X-Client-Platform': 'web' });
                const data = root && root.data && root.data.Response && root.data.Response.Data;
                if (root.code !== 0 || !data || !Number.isInteger(data.TotalCount) || data.TotalCount < 0 ||
                    data.TotalCount > 4000 || (expected !== null && expected !== data.TotalCount) ||
                    !Array.isArray(data.Accounts) || data.Accounts.length > 200) throw { status: 422 };
                expected = data.TotalCount;
                if (!data.Accounts.length && rows.length < expected) throw { status: 422 };
                for (const row of data.Accounts) {
                    const id = row.AccountId;
                    if (!((typeof id === 'string' && id.length > 0 && id.length <= 200) ||
                        (typeof id === 'number' && Number.isSafeInteger(id) && id >= 0)) || ids.has(String(id))) throw { status: 422 };
                    ids.add(String(id));
                    const safe = {};
                    for (const key of ['PackageName', 'CapacityUnit', 'CycleCapacityRemain', 'CycleCapacityRemainPrecise',
                        'CycleCapacitySize', 'CycleCapacitySizePrecise', 'CycleEndTime']) if (key in row) safe[key] = row[key];
                    rows.push(safe);
                }
                if (rows.length >= expected) break;
            }
            if (rows.length !== expected) throw { status: 422 };
            result = { code: 0, data: { Response: { Data: { TotalCount: rows.length, Accounts: rows } } } };
        } else if (provider === 'GROK') {
            const root = await read('/dashboard/api/dashboard/v2/usage-status?scope=user');
            result = { availability: root.availability };
            if ('weeklyIncludedUsage' in root) result.weeklyIncludedUsage = pick(root.weeklyIncludedUsage, ['usedCents', 'budgetCents', 'resetsAt']);
            if ('monthlyUsage' in root) result.monthlyUsage = pick(root.monthlyUsage, ['usedCents', 'resetsAt']);
            // A separate wallet may be unavailable; never discard valid primary quota.
            try { result.accountBalance = pick(await read('/dashboard/api/dashboard/get-complat-credit-balance', {}), ['usdRemainingCents']); }
            catch (_) { /* Quota remains usable when the optional wallet is unavailable. */ }
        } else return { kind: 'origin' };
        const raw = JSON.stringify(result);
        if (raw.length > limit) throw { status: 422 };
        return { kind: 'ok', raw };
    } catch (error) {
        const status = Number(error && error.status) || 0;
        return { kind: status === 401 || status === 403 ? 'login' : 'error', status,
            retrySeconds: status === 429 ? Math.min(86400, Number(error.retrySeconds) || 60) : 0 };
    } finally { clearTimeout(timer); }
}
