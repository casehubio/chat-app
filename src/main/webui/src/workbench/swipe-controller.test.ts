import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SwipeController } from './swipe-controller.js';
import type { ReactiveControllerHost } from 'lit';

if (!window.matchMedia) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false, media: query,
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
    addListener: vi.fn(), removeListener: vi.fn(),
    onchange: null, dispatchEvent: vi.fn(),
  }));
}

function mockHost(): ReactiveControllerHost & { updateComplete: Promise<boolean> } {
  return {
    updateComplete: Promise.resolve(true),
    addController() {},
    removeController() {},
    requestUpdate() {},
  };
}

function createDrawer(): HTMLElement {
  const el = document.createElement('div');
  el.style.width = '280px';
  el.style.position = 'fixed';
  document.body.appendChild(el);
  return el;
}

describe('SwipeController', () => {
  let host: ReturnType<typeof mockHost>;
  let leftDrawer: HTMLElement;
  let rightDrawer: HTMLElement;
  let backdrop: HTMLElement;
  let onOpen: ReturnType<typeof vi.fn<(side: 'left' | 'right') => void>>;
  let controller: SwipeController;

  beforeEach(() => {
    host = mockHost();
    leftDrawer = createDrawer();
    rightDrawer = createDrawer();
    backdrop = document.createElement('div');
    document.body.appendChild(backdrop);
    onOpen = vi.fn();

    controller = new SwipeController(host, {
      drawerQuery: (side) => side === 'left' ? leftDrawer : rightDrawer,
      backdropQuery: () => backdrop,
      onOpen,
    });
  });

  afterEach(() => {
    controller.hostDisconnected();
    document.body.innerHTML = '';
  });

  it('is a Lit reactive controller', () => {
    expect(controller.hostConnected).toBeDefined();
    expect(controller.hostDisconnected).toBeDefined();
  });

  it('ignores pointer events outside edge zones', () => {
    controller.hostConnected();
    const event = new PointerEvent('pointerdown', {
      clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
    });
    document.body.dispatchEvent(event);
    expect(leftDrawer.style.transform).toBe('');
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('starts tracking on pointerdown in left edge zone', () => {
    controller.hostConnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 25, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
    }));

    expect(leftDrawer.style.transform).not.toBe('');
  });

  it('opens drawer when dragged past 30% threshold', () => {
    controller.hostConnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 25, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointerup', {
      clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
    }));

    expect(onOpen).toHaveBeenCalledWith('left');
  });

  it('snaps back when drag is insufficient', () => {
    controller.hostConnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 20, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 40, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointerup', {
      clientX: 40, clientY: 200, pointerId: 1, bubbles: true,
    }));

    expect(onOpen).not.toHaveBeenCalled();
    expect(leftDrawer.style.transform).toBe('');
  });

  it('cancels tracking when vertical movement exceeds horizontal', () => {
    controller.hostConnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 8, clientY: 220, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 100, clientY: 220, pointerId: 1, bubbles: true,
    }));

    expect(leftDrawer.style.transform).toBe('');
  });

  it('detects right edge zone for member drawer', () => {
    controller.hostConnected();
    const w = window.innerWidth;

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: w - 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: w - 25, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: w - 100, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointerup', {
      clientX: w - 100, clientY: 200, pointerId: 1, bubbles: true,
    }));

    expect(onOpen).toHaveBeenCalledWith('right');
  });

  it('detaches listeners on hostDisconnected', () => {
    controller.hostConnected();
    controller.hostDisconnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
    }));

    expect(leftDrawer.style.transform).toBe('');
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('cleans up inline styles if disconnected mid-drag', () => {
    controller.hostConnected();

    document.body.dispatchEvent(new PointerEvent('pointerdown', {
      clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 25, clientY: 200, pointerId: 1, bubbles: true,
    }));
    document.body.dispatchEvent(new PointerEvent('pointermove', {
      clientX: 60, clientY: 200, pointerId: 1, bubbles: true,
    }));

    controller.hostDisconnected();

    expect(leftDrawer.style.transform).toBe('');
    expect(backdrop.style.opacity).toBe('');
  });

  describe('swipe-to-close', () => {
    let onClose: ReturnType<typeof vi.fn<(side: 'left' | 'right') => void>>;
    let closeController: SwipeController;

    beforeEach(() => {
      onClose = vi.fn();
      closeController = new SwipeController(mockHost(), {
        drawerQuery: (side) => side === 'left' ? leftDrawer : rightDrawer,
        backdropQuery: () => backdrop,
        onOpen,
        onClose,
        isOpenQuery: (side) => side === 'left',
      });
    });

    afterEach(() => {
      closeController.hostDisconnected();
    });

    it('closes left drawer when swiped left from drawer area', () => {
      closeController.hostConnected();

      document.body.dispatchEvent(new PointerEvent('pointerdown', {
        clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 80, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 0, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointerup', {
        clientX: 0, clientY: 200, pointerId: 1, bubbles: true,
      }));

      expect(onClose).toHaveBeenCalledWith('left');
      expect(onOpen).not.toHaveBeenCalled();
    });

    it('does not close when swipe distance is insufficient', () => {
      closeController.hostConnected();

      document.body.dispatchEvent(new PointerEvent('pointerdown', {
        clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 85, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 80, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointerup', {
        clientX: 80, clientY: 200, pointerId: 1, bubbles: true,
      }));

      expect(onClose).not.toHaveBeenCalled();
    });

    it('cancels close when vertical movement exceeds horizontal', () => {
      closeController.hostConnected();

      document.body.dispatchEvent(new PointerEvent('pointerdown', {
        clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 97, clientY: 220, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 0, clientY: 220, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointerup', {
        clientX: 0, clientY: 220, pointerId: 1, bubbles: true,
      }));

      expect(onClose).not.toHaveBeenCalled();
    });

    it('closes right drawer when swiped right', () => {
      const rightOpenController = new SwipeController(mockHost(), {
        drawerQuery: (side) => side === 'left' ? leftDrawer : rightDrawer,
        backdropQuery: () => backdrop,
        onOpen,
        onClose,
        isOpenQuery: (side) => side === 'right',
      });
      rightOpenController.hostConnected();
      const w = window.innerWidth;

      document.body.dispatchEvent(new PointerEvent('pointerdown', {
        clientX: w - 100, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: w - 80, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: w, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointerup', {
        clientX: w, clientY: 200, pointerId: 1, bubbles: true,
      }));

      expect(onClose).toHaveBeenCalledWith('right');
      rightOpenController.hostDisconnected();
    });

    it('prefers close gesture over edge open when drawer is already open', () => {
      closeController.hostConnected();

      document.body.dispatchEvent(new PointerEvent('pointerdown', {
        clientX: 5, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointermove', {
        clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
      }));
      document.body.dispatchEvent(new PointerEvent('pointerup', {
        clientX: 100, clientY: 200, pointerId: 1, bubbles: true,
      }));

      expect(onOpen).not.toHaveBeenCalled();
    });
  });

  describe('long-press detection', () => {
    function mockTouch(x: number, y: number): any {
      return { identifier: 0, target: document.body, clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y, radiusX: 0, radiusY: 0, rotationAngle: 0, force: 1 };
    }

    function touchEvent(type: string, x?: number, y?: number): TouchEvent {
      const init: any = { bubbles: true };
      if (x !== undefined && y !== undefined) init.touches = [mockTouch(x, y)];
      return new TouchEvent(type, init);
    }

    it('dispatches context-menu event after 500ms press', async () => {
      const events: CustomEvent[] = [];
      document.body.addEventListener('pages-event', (e) => events.push(e as CustomEvent));

      controller.hostConnected();
      document.body.dispatchEvent(touchEvent('touchstart', 100, 200));

      await new Promise(r => setTimeout(r, 550));
      expect(events.length).toBe(1);
      expect(events[0].detail.topic).toBe('context-menu');
      expect(events[0].detail.payload.x).toBe(100);
      expect(events[0].detail.payload.y).toBe(200);
    });

    it('cancels long-press if touch moves more than 10px', async () => {
      const events: CustomEvent[] = [];
      document.body.addEventListener('pages-event', (e) => events.push(e as CustomEvent));

      controller.hostConnected();
      document.body.dispatchEvent(touchEvent('touchstart', 100, 200));
      document.body.dispatchEvent(touchEvent('touchmove', 120, 200));

      await new Promise(r => setTimeout(r, 550));
      expect(events.length).toBe(0);
    });

    it('cancels long-press on touchend before threshold', async () => {
      const events: CustomEvent[] = [];
      document.body.addEventListener('pages-event', (e) => events.push(e as CustomEvent));

      controller.hostConnected();
      document.body.dispatchEvent(touchEvent('touchstart', 100, 200));
      document.body.dispatchEvent(new TouchEvent('touchend', { bubbles: true }));

      await new Promise(r => setTimeout(r, 550));
      expect(events.length).toBe(0);
    });
  });
});
