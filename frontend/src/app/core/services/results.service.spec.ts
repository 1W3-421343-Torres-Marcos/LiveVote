import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ResultsStreamEvent } from '../models/results.models';
import { EVENT_SOURCE_FACTORY, ResultsService } from './results.service';

class FakeEventSource {
  private readonly listeners = new Map<string, ((event: Event) => void)[]>();
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;

  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    const fn = listener as (event: Event) => void;
    const list = this.listeners.get(type) ?? [];
    list.push(fn);
    this.listeners.set(type, list);
  }

  removeEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    const fn = listener as (event: Event) => void;
    const list = this.listeners.get(type) ?? [];
    this.listeners.set(
      type,
      list.filter((item) => item !== fn),
    );
  }

  emit(type: string, data: unknown): void {
    const event = new MessageEvent(type, { data: JSON.stringify(data) });
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }

  triggerError(): void {
    this.onerror?.(new Event('error'));
  }

  close(): void {
    this.closed = true;
  }
}

describe('ResultsService SSE', () => {
  let service: ResultsService;
  let fakeSource: FakeEventSource;
  let requestedUrl: string | null;

  beforeEach(() => {
    fakeSource = new FakeEventSource();
    requestedUrl = null;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: EVENT_SOURCE_FACTORY,
          useValue: (url: string) => {
            requestedUrl = url;
            return fakeSource as unknown as EventSource;
          },
        },
      ],
    });
    service = TestBed.inject(ResultsService);
  });

  it('should emit results, heartbeat, and reconnecting events and close EventSource on unsubscribe', () => {
    const events: ResultsStreamEvent[] = [];
    const sub = service.streamResults('poll-123').subscribe((event) => events.push(event));

    expect(requestedUrl).toBe('/api/results/poll-123/stream');

    fakeSource.emit('results', {
      pollId: 'poll-123',
      title: 'Live?',
      status: 'OPEN',
      totalVotes: 3,
      options: [{ optionId: 'o1', text: 'Yes', displayOrder: 0, votes: 3 }],
      createdAt: '2026-11-27T20:00:00Z',
      closedAt: null,
    });
    fakeSource.emit('heartbeat', { timestamp: '2026-11-27T20:00:20Z' });
    fakeSource.triggerError();

    expect(events.length).toBe(3);
    expect(events[0].type).toBe('results');
    expect(events[1].type).toBe('heartbeat');
    expect(events[2].type).toBe('reconnecting');
    expect(fakeSource.closed).toBeFalse();

    sub.unsubscribe();
    expect(fakeSource.closed).toBeTrue();
  });
});
