// Shared chart runtime injected into every dashboard artifact iframe. Gives the
// agent-generated code robust, consistent SVG charts with built-in hover
// tooltips, number formatting, sparse axis labels, graceful empty states, and a
// built-in expand-to-fullscreen control — so it doesn't hand-roll SVG (which
// produced empty charts, "undefined" labels, no tooltips, and oversized charts
// that stretched to fill wide containers). Exposed as deepsql.charts.{bar,line,donut};
// each accepts a container (element or selector) and data as [{label,value}] or
// [[label,value]] or {columns,rows} straight from deepsql.query.
export const CHART_JS = String.raw`
(function(){
  if(!window.deepsql) window.deepsql = {};
  var NS='http://www.w3.org/2000/svg';
  function ns(t){ return document.createElementNS(NS,t); }
  function clear(el){ while(el.firstChild) el.removeChild(el.firstChild); }
  function esc(s){ return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];}); }
  function fmtNum(v){ if(v==null||v===''||isNaN(v)) return '—'; var n=Number(v);
    return n.toLocaleString(undefined,{maximumFractionDigits: Number.isInteger(n)?0:2}); }
  function resolve(el){ return typeof el==='string'?document.querySelector(el):el; }

  var TT=null;
  function tip(){ if(TT&&document.body.contains(TT)) return TT;
    TT=document.createElement('div');
    TT.style.cssText='position:fixed;z-index:99999;pointer-events:none;opacity:0;'+
      'background:#111318;color:#fff;font:12px/1.35 \'Maven Pro\',system-ui,sans-serif;'+
      'padding:7px 10px;border-radius:8px;box-shadow:0 6px 20px rgba(0,0,0,.20);'+
      'white-space:nowrap;transform:translate(-50%,-120%);transition:opacity .08s';
    document.body.appendChild(TT); return TT; }
  function showTip(e,label,valueHtml){ var t=tip();
    t.innerHTML='<b>'+valueHtml+'</b>'+(label?'<br>'+esc(label):'');
    t.style.left=e.clientX+'px'; t.style.top=e.clientY+'px'; t.style.opacity='1'; }
  function hideTip(){ if(TT) TT.style.opacity='0'; }

  // Accepts [{label,value}] | [[label,value]] | deepsql.query result {columns,rows}.
  function normalize(data,opts){
    opts=opts||{};
    var rows=data;
    if(data && !Array.isArray(data) && Array.isArray(data.rows)) rows=data.rows;
    return (rows||[]).map(function(d){
      if(Array.isArray(d)) return {label:String(d[0]), value:Number(d[1])};
      if(d && typeof d==='object') return {
        label:String(d[opts.labelKey]!=null?d[opts.labelKey]:(d.label!=null?d.label:(d.x!=null?d.x:d.name))),
        value:Number(d[opts.valueKey]!=null?d[opts.valueKey]:(d.value!=null?d.value:(d.y!=null?d.y:d.count)))
      };
      return {label:'',value:NaN};
    }).filter(function(d){ return d.label!=='undefined' && d.label!=='null' && d.label!=='' && !isNaN(d.value); });
  }
  function empty(el,msg){ clear(el); var d=document.createElement('div');
    d.style.cssText='color:var(--ds-ink-3,#8b909b);font-size:13px;padding:28px;text-align:center';
    d.textContent=msg||'No data for this range.'; el.appendChild(d); }

  // SVG is sized by an explicit height in CSS (not just the viewBox aspect
  // ratio), so a wide container can never inflate the rendered height — width
  // scales freely, height stays exactly H regardless of how wide the card is.
  function svgEl(W,H){ var s=ns('svg'); s.setAttribute('viewBox','0 0 '+W+' '+H);
    s.setAttribute('preserveAspectRatio','xMidYMid meet');
    s.style.display='block'; s.style.width='100%'; s.style.height=H+'px'; s.style.maxWidth='100%';
    return s; }
  // Truncates to fit its own slot width (~6.2px/char at this font size) so a
  // long label can never overlap its neighbor — better a clipped "Multivitam…"
  // with the full name in the tooltip than two labels running into each other.
  function truncateLabel(label,maxWidth){
    var charW=6.2, max=Math.max(3,Math.floor(maxWidth/charW));
    return label.length<=max ? label : label.slice(0,Math.max(1,max-1))+'…';
  }
  function xLabels(svg,rows,W,H,padL,bw){ var step=Math.max(1,Math.ceil(rows.length/6));
    rows.forEach(function(r,i){ if(i%step) return; var t=ns('text');
      t.setAttribute('x',padL+i*bw+bw/2); t.setAttribute('y',H-10); t.setAttribute('text-anchor','middle');
      t.setAttribute('font-size','12'); t.setAttribute('fill','#5b616e');
      t.textContent=truncateLabel(r.label,bw*step*0.92); svg.appendChild(t); }); }

  // ---- Expand-to-fullscreen chrome, shared by every chart type ----------
  // Wraps the caller's container in a positioned box with a corner button
  // (visible on hover/focus, like a card lift) that re-renders the same chart
  // at a larger size in a fullscreen overlay. Re-render, not a CSS zoom, so
  // labels/legend re-flow properly at the bigger size instead of just scaling.
  var overlayEl=null;
  function closeOverlay(){ if(overlayEl){ overlayEl.remove(); overlayEl=null; document.removeEventListener('keydown',onOverlayKey); } }
  function onOverlayKey(e){ if(e.key==='Escape') closeOverlay(); }
  function openOverlay(title, renderFn){
    closeOverlay();
    overlayEl=document.createElement('div');
    overlayEl.style.cssText='position:fixed;inset:0;z-index:100000;background:rgba(17,19,24,.5);'+
      'display:flex;align-items:center;justify-content:center;padding:32px;animation:dsqlFadeIn .15s ease-out';
    var panel=document.createElement('div');
    panel.style.cssText='background:var(--ds-surface,#fff);border-radius:16px;box-shadow:0 24px 60px rgba(0,0,0,.28);'+
      'width:100%;max-width:920px;max-height:calc(100vh - 64px);padding:24px 24px 20px;overflow:auto;'+
      'display:flex;flex-direction:column;gap:14px';
    var head=document.createElement('div');
    head.style.cssText='display:flex;align-items:center;justify-content:space-between;gap:12px';
    var h=document.createElement('div');
    h.style.cssText='font-size:15px;font-weight:600;color:var(--ds-ink,#111318)';
    h.textContent=title||'';
    var closeBtn=document.createElement('button');
    closeBtn.setAttribute('aria-label','Close');
    closeBtn.style.cssText='width:28px;height:28px;border:none;border-radius:8px;background:var(--ds-surface-2,#f6f7f9);'+
      'color:var(--ds-ink-2,#5b616e);cursor:pointer;font-size:15px;line-height:1;display:flex;align-items:center;justify-content:center';
    closeBtn.textContent='×';
    closeBtn.addEventListener('click',closeOverlay);
    head.appendChild(h); head.appendChild(closeBtn);
    var body=document.createElement('div');
    panel.appendChild(head); panel.appendChild(body);
    overlayEl.appendChild(panel);
    overlayEl.addEventListener('mousedown',function(e){ if(e.target===overlayEl) closeOverlay(); });
    document.addEventListener('keydown',onOverlayKey);
    document.body.appendChild(overlayEl);
    renderFn(body);
  }
  if(!document.getElementById('dsql-chart-style')){
    var st=document.createElement('style'); st.id='dsql-chart-style';
    st.textContent='@keyframes dsqlFadeIn{from{opacity:0}to{opacity:1}}'+
      '.dsql-chart-wrap{position:relative}'+
      '.dsql-chart-expand{position:absolute;top:0;right:0;width:26px;height:26px;border:none;border-radius:7px;'+
      'background:var(--ds-surface,#fff);color:var(--ds-ink-3,#8b909b);cursor:pointer;opacity:0;'+
      'transition:opacity .12s,color .12s,background .12s;display:flex;align-items:center;justify-content:center;'+
      'box-shadow:0 1px 4px rgba(0,0,0,.08)}'+
      '.dsql-chart-wrap:hover .dsql-chart-expand,.dsql-chart-expand:focus-visible{opacity:1}'+
      '.dsql-chart-expand:hover{color:var(--ds-ink,#111318);background:var(--ds-surface-2,#f6f7f9)}';
    document.head.appendChild(st);
  }
  var EXPAND_ICON='<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '+
    'stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/>'+
    '<line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>';
  // Wraps el's content and installs the expand button once per container
  // (idempotent — re-render calls on the same el just update chartRenderFn).
  function withExpand(el, title, renderAt){
    var wrap=el, body=el;
    if(!el.__dsqlChartWrap){
      clear(el);
      wrap=document.createElement('div'); wrap.className='dsql-chart-wrap';
      body=document.createElement('div');
      var btn=document.createElement('button');
      btn.className='dsql-chart-expand'; btn.setAttribute('aria-label','Expand chart'); btn.innerHTML=EXPAND_ICON;
      btn.addEventListener('click',function(e){
        e.stopPropagation();
        openOverlay(title, function(container){ renderAt(container, true); });
      });
      wrap.appendChild(body); wrap.appendChild(btn);
      el.appendChild(wrap);
      el.__dsqlChartWrap=wrap; el.__dsqlChartBody=body;
    } else {
      body=el.__dsqlChartBody; clear(body);
    }
    renderAt(body, false);
  }

  function bar(el,data,opts){ opts=opts||{}; el=resolve(el); if(!el) return;
    withExpand(el, opts.title, function(target, big){
      var rows=normalize(data,opts); if(!rows.length) return empty(target,opts.emptyText);
      var fmt=opts.valueFormat||fmtNum, W=big?960:640, H=big?420:(opts.height||240), padT=12,padB=34,padX=6;
      var max=Math.max.apply(null,rows.map(function(r){return r.value})); if(!(max>0)) max=1;
      var iw=W-padX*2, ih=H-padT-padB, bw=iw/rows.length, bar=Math.min(bw*0.64,44);
      var svg=svgEl(W,H);
      rows.forEach(function(r,i){ var h=Math.max(1,(r.value/max)*ih), x=padX+i*bw+(bw-bar)/2, y=padT+ih-h;
        var rect=ns('rect'); rect.setAttribute('x',x); rect.setAttribute('y',y); rect.setAttribute('width',bar);
        rect.setAttribute('height',h); rect.setAttribute('rx',3); rect.setAttribute('fill',opts.color||'#111318');
        rect.style.cursor='default'; rect.style.transition='opacity .1s';
        rect.addEventListener('mouseenter',function(){ rect.style.opacity='.72'; });
        rect.addEventListener('mousemove',function(e){ showTip(e,r.label,fmt(r.value)); });
        rect.addEventListener('mouseleave',function(){ rect.style.opacity='1'; hideTip(); });
        svg.appendChild(rect); });
      xLabels(svg,rows,W,H,padX,bw); target.appendChild(svg);
    });
  }

  function line(el,data,opts){ opts=opts||{}; el=resolve(el); if(!el) return;
    withExpand(el, opts.title, function(target, big){
      var rows=normalize(data,opts); if(!rows.length) return empty(target,opts.emptyText);
      var fmt=opts.valueFormat||fmtNum, W=big?960:640, H=big?420:(opts.height||240), padT=12,padB=34,padX=8;
      var max=Math.max.apply(null,rows.map(function(r){return r.value})); if(!(max>0)) max=1;
      var iw=W-padX*2, ih=H-padT-padB, n=rows.length, dx=n>1?iw/(n-1):0;
      var pts=rows.map(function(r,i){ return {x:padX+(n>1?i*dx:iw/2), y:padT+ih-(r.value/max)*ih, r:r}; });
      var svg=svgEl(W,H);
      var area=ns('path'); var dpath='M'+pts.map(function(p){return p.x+' '+p.y}).join(' L ');
      area.setAttribute('d',dpath+' L '+pts[pts.length-1].x+' '+(padT+ih)+' L '+pts[0].x+' '+(padT+ih)+' Z');
      area.setAttribute('fill',opts.fill||'rgba(17,19,24,.06)'); svg.appendChild(area);
      var pl=ns('path'); pl.setAttribute('d',dpath); pl.setAttribute('fill','none');
      pl.setAttribute('stroke',opts.color||'#111318'); pl.setAttribute('stroke-width','2'); pl.setAttribute('stroke-linejoin','round'); svg.appendChild(pl);
      pts.forEach(function(p){ var c=ns('circle'); c.setAttribute('cx',p.x); c.setAttribute('cy',p.y); c.setAttribute('r','9');
        c.setAttribute('fill','transparent'); c.style.cursor='default';
        c.addEventListener('mousemove',function(e){ dot.setAttribute('r','4'); showTip(e,p.r.label,fmt(p.r.value)); });
        c.addEventListener('mouseleave',function(){ dot.setAttribute('r','2.5'); hideTip(); });
        var dot=ns('circle'); dot.setAttribute('cx',p.x); dot.setAttribute('cy',p.y); dot.setAttribute('r','2.5'); dot.setAttribute('fill',opts.color||'#111318');
        svg.appendChild(dot); svg.appendChild(c); });
      xLabels(svg,rows,W,H,padX,n>1?dx:iw); target.appendChild(svg);
    });
  }

  function donut(el,data,opts){ opts=opts||{}; el=resolve(el); if(!el) return;
    withExpand(el, opts.title, function(target, big){
      var rows=normalize(data,opts); if(!rows.length) return empty(target,opts.emptyText);
      var fmt=opts.valueFormat||fmtNum, S=big?320:240, cx=S/2, cy=S/2, rO=S*0.458, rI=S*0.275;
      var total=rows.reduce(function(a,r){return a+r.value},0); if(!(total>0)) return empty(target,opts.emptyText);
      var greys=['#111318','#3a3f4a','#5b616e','#8b909b','#b6bac2','#d7dae0','#eceef2'];
      var wrap=document.createElement('div'); wrap.style.cssText='display:flex;gap:20px;align-items:center;flex-wrap:wrap';
      var svg=svgEl(S,S); svg.setAttribute('width',S); svg.style.width=S+'px'; svg.style.maxWidth=S+'px';
      var a0=-Math.PI/2;
      rows.forEach(function(r,i){ var ang=r.value/total*Math.PI*2, a1=a0+ang, large=ang>Math.PI?1:0;
        var p=ns('path'); p.setAttribute('d',
          'M'+(cx+rO*Math.cos(a0))+' '+(cy+rO*Math.sin(a0))+
          ' A'+rO+' '+rO+' 0 '+large+' 1 '+(cx+rO*Math.cos(a1))+' '+(cy+rO*Math.sin(a1))+
          ' L'+(cx+rI*Math.cos(a1))+' '+(cy+rI*Math.sin(a1))+
          ' A'+rI+' '+rI+' 0 '+large+' 0 '+(cx+rI*Math.cos(a0))+' '+(cy+rI*Math.sin(a0))+' Z');
        p.setAttribute('fill',greys[i%greys.length]); p.style.cursor='default'; p.style.transition='opacity .1s';
        var pct=(r.value/total*100);
        p.addEventListener('mouseenter',function(){ p.style.opacity='.75'; });
        p.addEventListener('mousemove',function(e){ showTip(e,r.label,fmt(r.value)+' ('+pct.toFixed(1)+'%)'); });
        p.addEventListener('mouseleave',function(){ p.style.opacity='1'; hideTip(); });
        svg.appendChild(p); a0=a1; });
      var legend=document.createElement('div'); legend.style.cssText='font-size:13px;color:var(--ds-ink-2,#5b616e)';
      rows.slice(0,7).forEach(function(r,i){ var row=document.createElement('div');
        row.style.cssText='display:flex;align-items:center;gap:8px;margin:4px 0';
        row.innerHTML='<span style="width:10px;height:10px;border-radius:2px;background:'+greys[i%greys.length]+';display:inline-block"></span>'+
          '<span>'+esc(r.label)+'</span><b style="margin-left:auto;color:var(--ds-ink,#111318)">'+(r.value/total*100).toFixed(1)+'%</b>';
        legend.appendChild(row); });
      wrap.appendChild(svg); wrap.appendChild(legend); target.appendChild(wrap);
    });
  }

  window.deepsql.charts = { bar: bar, line: line, donut: donut, format: fmtNum };
})();
`
