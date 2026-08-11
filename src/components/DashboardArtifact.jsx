import { useRef, useEffect, useCallback, useState } from 'react'
import { dashboardQueryAPI } from '@/lib/api/client'
import { THEME_CSS } from './dashboardTheme'
import { CHART_JS } from './dashboardChartLib'

// Bridge injected into the sandboxed iframe. It gives the agent-generated code a
// tiny read-only data API (deepsql.query) that round-trips through the parent via
// postMessage — the iframe has an opaque origin (sandbox=allow-scripts, no
// same-origin) so it can't touch cookies/DOM or hit the DB itself. It also
// reports document height (for seamless sizing) and runtime errors to the parent.
const BRIDGE = `
(function(){
  var pending = {}, seq = 0;
  function send(m){ try{ parent.postMessage(m, '*'); }catch(e){} }
  window.deepsql = {
    connectionId: window.__DSQL_CONN__ || null,
    query: function(sql, opts){
      return new Promise(function(resolve, reject){
        var id = 'q' + (++seq);
        pending[id] = { resolve: resolve, reject: reject };
        send({ __deepsql:true, type:'query', id:id, sql:String(sql), limit: opts && opts.limit });
      });
    },
    ready: function(fn){
      if (document.readyState !== 'loading') setTimeout(fn, 0);
      else document.addEventListener('DOMContentLoaded', fn);
    }
  };
  window.addEventListener('message', function(e){
    var d = e.data;
    if (!d || d.__deepsql !== true || d.type !== 'result') return;
    var p = pending[d.id]; if (!p) return; delete pending[d.id];
    if (d.error) p.reject(new Error(d.error));
    else p.resolve({ columns: d.columns || [], rows: d.rows || [] });
  });
  // Report only the content's own height, never the iframe's current rendered
  // height — scrollHeight on a body with height:auto reflects content size and
  // can't be inflated by whatever height the parent last set, so this can't
  // feed back into itself. Debounced and deduped so parent-side layout thrash
  // (e.g. a page scroll) can't retrigger it with the same value.
  var lastReported=-1, reportTimer=null;
  function reportHeight(){
    if (reportTimer) return;
    reportTimer = setTimeout(function(){
      reportTimer = null;
      var h = document.documentElement ? document.documentElement.scrollHeight : 0;
      if (h === lastReported) return;
      lastReported = h;
      send({ __deepsql:true, type:'height', value: h });
    }, 50);
  }
  window.addEventListener('load', function(){
    reportHeight();
    try { new ResizeObserver(reportHeight).observe(document.body); } catch(e){}
  });
  window.addEventListener('error', function(e){
    send({ __deepsql:true, type:'jserror', message: (e && e.message) || 'script error' });
  });
})();
`

// Content-Security-Policy for the artifact: no external network of any kind
// (default-src 'none'), inline styles/scripts only, data: images. postMessage
// isn't gated by connect-src, so the bridge still works while fetch/XHR/CDNs are
// hard-blocked — defense in depth on top of the sandbox.
const CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
  + "img-src data:; font-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'"

function buildSrcDoc(html, connectionId) {
  const head = `<meta charset="utf-8">`
    + `<meta http-equiv="Content-Security-Policy" content="${CSP}">`
    + `<style>${THEME_CSS}</style>`
    + `<script>window.__DSQL_CONN__=${JSON.stringify(connectionId)};</script>`
    + `<script>${BRIDGE}</script>`
    + `<script>${CHART_JS}</script>`
  // Inject the bridge as the very first thing in <head> so it runs before the
  // agent's own scripts. Fall back gracefully if the doc has no <head>/<html>.
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head[^>]*>/i, (m) => m + head)
  if (/<html[^>]*>/i.test(html)) return html.replace(/<html[^>]*>/i, (m) => `${m}<head>${head}</head>`)
  return `<!doctype html><html><head>${head}</head><body>${html}</body></html>`
}

// A dashboard fans out one query per widget, and generated code can re-query on
// every filter change — so the parent throttles and isolates:
//  - at most MAX_CONCURRENT queries in flight (the rest queue);
//  - each query is INDEPENDENT (resolved/rejected by its own id) so one widget's
//    failure never touches another;
//  - each query has its own timeout — a slow/hung one aborts, reports "timed out"
//    for that widget only, and frees its slot so the others aren't stalled;
//  - a gentle backoff-retry on 503 (the shared rate limiter) so a burst self-heals;
//  - a hard cap on total queries so a runaway artifact can't storm the backend.
const MAX_CONCURRENT = 6
const MAX_TOTAL_QUERIES = 400
const QUERY_TIMEOUT_MS = 25000
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const REDUCED_MOTION = typeof window !== 'undefined'
  && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

export default function DashboardArtifact({ connectionId, html, onError, queryFn }) {
  const iframeRef = useRef(null)
  const [height, setHeight] = useState(600)
  const [loaded, setLoaded] = useState(false)
  const queueRef = useRef([])
  const inflightRef = useRef(0)
  const totalRef = useRef(0)

  // How a query actually runs — the authed broker by default, or an injected fn
  // (e.g. the public/token endpoint) for a shared viewer. Held in a ref so a
  // later-resolved connectionId/queryFn is always picked up (pump captures once).
  const runQueryRef = useRef(null)
  runQueryRef.current = queryFn || ((sql, limit, signal) => dashboardQueryAPI.run(connectionId, sql, limit, signal))

  function post(msg) {
    iframeRef.current?.contentWindow?.postMessage(msg, '*')
  }

  const pump = useCallback(() => {
    while (inflightRef.current < MAX_CONCURRENT && queueRef.current.length > 0) {
      const job = queueRef.current.shift()
      inflightRef.current += 1
      runJob(job).finally(() => { inflightRef.current -= 1; pump() })
    }
  }, [])

  async function runJob(job) {
    for (let attempt = 0; ; attempt += 1) {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), QUERY_TIMEOUT_MS)
      try {
        const res = await runQueryRef.current(job.sql, job.limit, controller.signal)
        clearTimeout(timer)
        post({ __deepsql: true, type: 'result', id: job.id, columns: res.columns, rows: res.rows })
        return
      } catch (err) {
        clearTimeout(timer)
        const timedOut = controller.signal.aborted
        const status = err?.response?.status
        // Retry only transient rate-limit rejections — never a timeout (that widget
        // is already too slow; back off it, not the whole dashboard).
        if (status === 503 && !timedOut && attempt < 4) {
          await sleep(400 * (attempt + 1) + Math.floor(Math.random() * 250))
          continue
        }
        post({
          __deepsql: true, type: 'result', id: job.id,
          error: timedOut ? 'Timed out' : (err?.message || 'query failed'),
        })
        return
      }
    }
  }

  const onMessage = useCallback((e) => {
    if (e.source !== iframeRef.current?.contentWindow) return
    const d = e.data
    if (!d || d.__deepsql !== true) return
    if (d.type === 'query') {
      if (totalRef.current >= MAX_TOTAL_QUERIES) {
        post({ __deepsql: true, type: 'result', id: d.id, error: 'This dashboard made too many requests.' })
        return
      }
      totalRef.current += 1
      queueRef.current.push({ id: d.id, sql: d.sql, limit: d.limit })
      pump()
    } else if (d.type === 'height' && typeof d.value === 'number' && d.value > 0) {
      setHeight(Math.min(Math.ceil(d.value) + 4, 20000))
    } else if (d.type === 'jserror') {
      onError?.(d.message)
    }
  }, [connectionId, onError, pump])

  useEffect(() => {
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [onMessage])

  // New artifact (generate/edit) reloads the iframe — reset the throttle so a
  // fresh dashboard isn't blocked by the prior one's runaway cap, and fade the
  // new one in rather than popping at whatever height it first reports.
  useEffect(() => {
    queueRef.current = []
    inflightRef.current = 0
    totalRef.current = 0
    setLoaded(false)
  }, [html])

  return (
    <iframe
      ref={iframeRef}
      title="Dashboard"
      sandbox="allow-scripts"
      srcDoc={buildSrcDoc(html || '', connectionId)}
      onLoad={() => setLoaded(true)}
      style={{
        width: '100%',
        height,
        border: 'none',
        display: 'block',
        background: '#f8fafc',
        opacity: loaded ? 1 : 0,
        // Height snaps immediately, never transitions — animating it risked measuring
        // the iframe's own in-transition rendered height as if it were new content,
        // a feedback loop that made the page grow on every scroll/resize tick.
        transition: REDUCED_MOTION ? 'opacity 150ms linear' : 'opacity 320ms cubic-bezier(0.2, 0.8, 0.2, 1)',
      }}
    />
  )
}
