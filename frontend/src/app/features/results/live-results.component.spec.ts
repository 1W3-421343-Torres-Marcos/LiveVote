import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, throwError } from 'rxjs';
import { ResultsStreamEvent } from '../../core/models/results.models';
import { AuthService } from '../../core/services/auth.service';
import { ResultsService } from '../../core/services/results.service';
import { LiveResultsComponent } from './live-results.component';

describe('LiveResultsComponent', () => {
  let resultsServiceSpy: jasmine.SpyObj<ResultsService>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let streamSubject: Subject<ResultsStreamEvent>;
  let teardownCount: number;

  beforeEach(async () => {
    teardownCount = 0;
    streamSubject = new Subject<ResultsStreamEvent>();
    resultsServiceSpy = jasmine.createSpyObj<ResultsService>('ResultsService', [
      'streamResults',
      'getResults',
    ]);
    authServiceSpy = jasmine.createSpyObj<AuthService>('AuthService', ['isAuthenticated']);
    authServiceSpy.isAuthenticated.and.returnValue(false);

    resultsServiceSpy.streamResults.and.returnValue(
      new Observable<ResultsStreamEvent>((observer) => {
        const innerSub = streamSubject.subscribe(observer);
        return () => {
          teardownCount++;
          innerSub.unsubscribe();
        };
      }),
    );

    await TestBed.configureTestingModule({
      imports: [LiveResultsComponent],
      providers: [
        provideRouter([]),
        { provide: ResultsService, useValue: resultsServiceSpy },
        { provide: AuthService, useValue: authServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'poll-live' }) } },
        },
      ],
    }).compileComponents();
  });

  it('should render bars and percentages from SSE updates and close subscription on destroy', () => {
    const fixture = TestBed.createComponent(LiveResultsComponent);
    fixture.detectChanges();

    streamSubject.next({
      type: 'results',
      payload: {
        pollId: 'poll-live',
        title: 'Modo proyector',
        status: 'OPEN',
        totalVotes: 4,
        options: [
          { optionId: 'a', text: 'Angular', displayOrder: 0, votes: 3 },
          { optionId: 'b', text: 'React', displayOrder: 1, votes: 1 },
        ],
        createdAt: '2026-11-27T20:00:00Z',
        closedAt: null,
      },
    });
    streamSubject.next({
      type: 'heartbeat',
      payload: { timestamp: '2026-11-27T20:00:20Z' },
    });
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Modo proyector');
    expect(el.textContent).toContain('3 (75%)');
    expect(el.textContent).toContain('1 (25%)');
    expect(el.textContent).toContain('SSE conectado');

    fixture.destroy();
    expect(teardownCount).toBe(1);
  });

  it('should stop stream and show 404 when poll does not exist', () => {
    resultsServiceSpy.getResults.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );

    const fixture = TestBed.createComponent(LiveResultsComponent);
    fixture.detectChanges();

    streamSubject.next({ type: 'reconnecting' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Encuesta no encontrada');
    expect(teardownCount).toBe(1);
  });
});
