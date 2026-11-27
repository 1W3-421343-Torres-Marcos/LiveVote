import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, NgZone, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { HeartbeatEvent, PollResultsResponse, ResultsStreamEvent } from '../models/results.models';

export type EventSourceFactory = (url: string) => EventSource;

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url),
});

@Injectable({ providedIn: 'root' })
export class ResultsService {
  private readonly http = inject(HttpClient);
  private readonly ngZone = inject(NgZone);
  private readonly eventSourceFactory = inject(EVENT_SOURCE_FACTORY);

  getResults(pollId: string): Observable<PollResultsResponse> {
    return this.http.get<PollResultsResponse>(`/api/results/${encodeURIComponent(pollId)}`);
  }

  streamResults(pollId: string): Observable<ResultsStreamEvent> {
    const url = `/api/results/${encodeURIComponent(pollId)}/stream`;
    return new Observable<ResultsStreamEvent>((observer) => {
      const eventSource = this.eventSourceFactory(url);

      const onResults = (event: Event) => {
        const message = event as MessageEvent<string>;
        this.ngZone.run(() => {
          try {
            const payload = JSON.parse(message.data) as PollResultsResponse;
            observer.next({ type: 'results', payload });
          } catch (error) {
            observer.error(error);
          }
        });
      };

      const onHeartbeat = (event: Event) => {
        const message = event as MessageEvent<string>;
        this.ngZone.run(() => {
          try {
            const payload = JSON.parse(message.data) as HeartbeatEvent;
            observer.next({ type: 'heartbeat', payload });
          } catch {
            observer.next({ type: 'heartbeat', payload: { timestamp: new Date().toISOString() } });
          }
        });
      };

      const onError = () => {
        this.ngZone.run(() => {
          observer.next({ type: 'reconnecting' });
        });
      };

      eventSource.addEventListener('results', onResults);
      eventSource.addEventListener('heartbeat', onHeartbeat);
      eventSource.onerror = onError;

      return () => {
        eventSource.removeEventListener('results', onResults);
        eventSource.removeEventListener('heartbeat', onHeartbeat);
        eventSource.onerror = null;
        eventSource.close();
      };
    });
  }
}
