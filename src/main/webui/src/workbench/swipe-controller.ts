import type { ReactiveController, ReactiveControllerHost } from 'lit';

export interface SwipeOptions {
  drawerQuery: (side: 'left' | 'right') => HTMLElement | null;
  backdropQuery: () => HTMLElement | null;
  onOpen: (side: 'left' | 'right') => void;
  onClose?: (side: 'left' | 'right') => void;
  isOpenQuery?: (side: 'left' | 'right') => boolean;
  edgeWidth?: number;
  drawerWidth?: number;
  distanceThreshold?: number;
  velocityThreshold?: number;
}

interface PointerSample {
  x: number;
  t: number;
}

export class SwipeController implements ReactiveController {
  private _host: ReactiveControllerHost;
  private _options: Required<SwipeOptions>;
  private _tracking = false;
  private _side: 'left' | 'right' = 'left';
  private _gesture: 'open' | 'close' = 'open';
  private _startX = 0;
  private _startY = 0;
  private _intentConfirmed = false;
  private _pointerId: number | null = null;
  private _samples: PointerSample[] = [];
  private _attached = false;
  private _reducedMotion = false;
  private _longPressTimer: ReturnType<typeof setTimeout> | null = null;
  private _longPressStartPos: { x: number; y: number } | null = null;

  constructor(host: ReactiveControllerHost, options: SwipeOptions) {
    this._host = host;
    this._options = {
      edgeWidth: 20,
      drawerWidth: 280,
      distanceThreshold: 0.3,
      velocityThreshold: 0.5,
      onClose: () => {},
      isOpenQuery: () => false,
      ...options,
    };
    host.addController(this);
  }

  hostConnected() {
    try { this._reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { this._reducedMotion = false; }
    this._attachListeners();
  }

  hostDisconnected() {
    this._cleanupMidDrag();
    this._detachListeners();
  }

  private _attachListeners() {
    if (this._attached) return;
    const el = this._host instanceof HTMLElement ? this._host : document.body;
    document.body.addEventListener('pointerdown', this._onPointerDown);
    el.addEventListener('touchstart', this._onTouchStart, { passive: true });
    el.addEventListener('touchmove', this._onTouchMove, { passive: true });
    el.addEventListener('touchend', this._onTouchEnd, { passive: true });
    this._attached = true;
  }

  private _detachListeners() {
    if (!this._attached) return;
    const el = this._host instanceof HTMLElement ? this._host : document.body;
    document.body.removeEventListener('pointerdown', this._onPointerDown);
    document.body.removeEventListener('pointermove', this._onPointerMove);
    document.body.removeEventListener('pointerup', this._onPointerUp);
    document.body.removeEventListener('pointercancel', this._onPointerUp);
    el.removeEventListener('touchstart', this._onTouchStart);
    el.removeEventListener('touchmove', this._onTouchMove);
    el.removeEventListener('touchend', this._onTouchEnd);
    this._cancelLongPress();
    this._attached = false;
  }

  private _cleanupMidDrag() {
    if (!this._tracking) return;
    const drawer = this._options.drawerQuery(this._side);
    const backdrop = this._options.backdropQuery();
    if (drawer) drawer.style.transform = '';
    if (backdrop) backdrop.style.opacity = '';
    if (this._pointerId != null) {
      try { document.body.releasePointerCapture(this._pointerId); } catch {}
    }
    this._tracking = false;
    this._intentConfirmed = false;
    this._pointerId = null;
    this._samples = [];
  }

  private _onPointerDown = (e: PointerEvent) => {
    const { edgeWidth, isOpenQuery } = this._options;
    const w = window.innerWidth;
    let side: 'left' | 'right';
    let gesture: 'open' | 'close';

    if (isOpenQuery('left') && e.clientX <= this._options.drawerWidth) {
      side = 'left';
      gesture = 'close';
    } else if (isOpenQuery('right') && e.clientX >= w - this._options.drawerWidth) {
      side = 'right';
      gesture = 'close';
    } else if (isOpenQuery('left') || isOpenQuery('right')) {
      side = isOpenQuery('left') ? 'left' : 'right';
      gesture = 'close';
    } else if (e.clientX <= edgeWidth) {
      side = 'left';
      gesture = 'open';
    } else if (e.clientX >= w - edgeWidth) {
      side = 'right';
      gesture = 'open';
    } else {
      return;
    }

    this._side = side;
    this._gesture = gesture;
    this._startX = e.clientX;
    this._startY = e.clientY;
    this._tracking = true;
    this._intentConfirmed = false;
    this._pointerId = e.pointerId;
    this._samples = [{ x: e.clientX, t: e.timeStamp }];

    try { document.body.setPointerCapture(e.pointerId); } catch {}

    document.body.addEventListener('pointermove', this._onPointerMove);
    document.body.addEventListener('pointerup', this._onPointerUp);
    document.body.addEventListener('pointercancel', this._onPointerUp);
  };

  private _onPointerMove = (e: PointerEvent) => {
    if (!this._tracking) return;

    const dx = Math.abs(e.clientX - this._startX);
    const dy = Math.abs(e.clientY - this._startY);

    if (!this._intentConfirmed) {
      if (dx + dy < 10) return;
      if (dy > dx) {
        this._cancelDrag();
        return;
      }
      this._intentConfirmed = true;
    }

    e.preventDefault();

    this._samples.push({ x: e.clientX, t: e.timeStamp });
    if (this._samples.length > 6) this._samples.shift();

    if (this._reducedMotion) return;

    const { drawerWidth } = this._options;
    const drawer = this._options.drawerQuery(this._side);
    const backdrop = this._options.backdropQuery();
    if (!drawer) return;

    let delta: number;
    if (this._gesture === 'open') {
      if (this._side === 'left') {
        delta = Math.max(0, Math.min(drawerWidth, e.clientX - this._startX));
        drawer.style.transform = `translateX(calc(-100% + ${delta}px))`;
      } else {
        delta = Math.max(0, Math.min(drawerWidth, this._startX - e.clientX));
        drawer.style.transform = `translateX(calc(100% - ${delta}px))`;
      }
      const progress = delta / drawerWidth;
      if (backdrop) backdrop.style.opacity = String(progress * 0.5);
    } else {
      if (this._side === 'left') {
        delta = Math.max(0, Math.min(drawerWidth, this._startX - e.clientX));
        drawer.style.transform = `translateX(-${delta}px)`;
      } else {
        delta = Math.max(0, Math.min(drawerWidth, e.clientX - this._startX));
        drawer.style.transform = `translateX(${delta}px)`;
      }
      const progress = 1 - delta / drawerWidth;
      if (backdrop) backdrop.style.opacity = String(progress * 0.5);
    }
  };

  private _onPointerUp = (e: PointerEvent) => {
    if (!this._tracking) return;

    document.body.removeEventListener('pointermove', this._onPointerMove);
    document.body.removeEventListener('pointerup', this._onPointerUp);
    document.body.removeEventListener('pointercancel', this._onPointerUp);

    if (this._pointerId != null) {
      try { document.body.releasePointerCapture(this._pointerId); } catch {}
    }

    if (!this._intentConfirmed) {
      this._tracking = false;
      this._pointerId = null;
      return;
    }

    const { drawerWidth, distanceThreshold, velocityThreshold } = this._options;
    let totalDelta: number;
    if (this._gesture === 'open') {
      totalDelta = this._side === 'left'
        ? e.clientX - this._startX
        : this._startX - e.clientX;
    } else {
      totalDelta = this._side === 'left'
        ? this._startX - e.clientX
        : e.clientX - this._startX;
    }

    const distanceMet = totalDelta / drawerWidth > distanceThreshold;
    const velocity = this._computeVelocity(e.timeStamp);
    const velocityMet = velocity > velocityThreshold;

    const drawer = this._options.drawerQuery(this._side);
    const backdrop = this._options.backdropQuery();

    if (distanceMet || velocityMet) {
      if (this._gesture === 'open') {
        if (drawer) drawer.style.transform = 'translateX(0)';
        this._options.onOpen(this._side);
      } else {
        this._options.onClose(this._side);
      }
      this._host.updateComplete.then(() => {
        if (drawer) drawer.style.transform = '';
        if (backdrop) backdrop.style.opacity = '';
      });
    } else {
      if (drawer) drawer.style.transform = '';
      if (backdrop) backdrop.style.opacity = '';
    }

    this._tracking = false;
    this._pointerId = null;
    this._samples = [];
  };

  private _cancelDrag() {
    document.body.removeEventListener('pointermove', this._onPointerMove);
    document.body.removeEventListener('pointerup', this._onPointerUp);
    document.body.removeEventListener('pointercancel', this._onPointerUp);
    if (this._pointerId != null) {
      try { document.body.releasePointerCapture(this._pointerId); } catch {}
    }
    this._tracking = false;
    this._pointerId = null;
    this._samples = [];
  }

  private _computeVelocity(now: number): number {
    if (this._samples.length < 2) return 0;
    const windowMs = 100;
    const recent = this._samples.filter(s => now - s.t <= windowMs);
    if (recent.length < 2) {
      const first = this._samples[0];
      const last = this._samples[this._samples.length - 1];
      const dt = last.t - first.t;
      return dt >= 5 ? Math.abs(last.x - first.x) / dt : 0;
    }
    const first = recent[0];
    const last = recent[recent.length - 1];
    const dt = last.t - first.t;
    return dt >= 5 ? Math.abs(last.x - first.x) / dt : 0;
  }

  private _cancelLongPress() {
    if (this._longPressTimer) {
      clearTimeout(this._longPressTimer);
      this._longPressTimer = null;
    }
    this._longPressStartPos = null;
  }

  private _onTouchStart = (e: TouchEvent) => {
    const touch = e.touches[0];
    if (!touch) return;
    this._longPressStartPos = { x: touch.clientX, y: touch.clientY };
    const target = e.target;
    this._longPressTimer = setTimeout(() => {
      const el = this._host instanceof HTMLElement ? this._host : document.body;
      el.dispatchEvent(new CustomEvent('pages-event', {
        bubbles: true, composed: true,
        detail: { topic: 'context-menu', payload: { x: touch.clientX, y: touch.clientY, target } },
      }));
      this._longPressTimer = null;
    }, 500);
  };

  private _onTouchMove = (e: TouchEvent) => {
    if (!this._longPressTimer || !this._longPressStartPos) return;
    const touch = e.touches[0];
    if (!touch) return;
    const dx = touch.clientX - this._longPressStartPos.x;
    const dy = touch.clientY - this._longPressStartPos.y;
    if (Math.sqrt(dx * dx + dy * dy) > 10) {
      this._cancelLongPress();
    }
  };

  private _onTouchEnd = () => {
    this._cancelLongPress();
  };
}
