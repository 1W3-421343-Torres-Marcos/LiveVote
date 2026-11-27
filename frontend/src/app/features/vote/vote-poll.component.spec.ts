import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DeviceIdService } from '../../core/services/device-id.service';
import { PollService } from '../../core/services/poll.service';
import { VotePollComponent } from './vote-poll.component';

describe('VotePollComponent', () => {
  let pollServiceSpy: jasmine.SpyObj<PollService>;
  let deviceIdServiceSpy: jasmine.SpyObj<DeviceIdService>;

  const samplePoll = {
    id: 'poll-1',
    title: '¿Café o mate?',
    status: 'OPEN' as const,
    options: [
      { id: 'opt-1', text: 'Café', displayOrder: 0 },
      { id: 'opt-2', text: 'Mate', displayOrder: 1 },
    ],
    createdAt: '2026-11-27T20:00:00Z',
    closedAt: null,
  };

  beforeEach(async () => {
    pollServiceSpy = jasmine.createSpyObj<PollService>('PollService', ['getPublicPoll', 'castVote']);
    deviceIdServiceSpy = jasmine.createSpyObj<DeviceIdService>('DeviceIdService', ['getDeviceId']);
    deviceIdServiceSpy.getDeviceId.and.returnValue('device-uuid-1');

    await TestBed.configureTestingModule({
      imports: [VotePollComponent],
      providers: [
        provideRouter([]),
        { provide: PollService, useValue: pollServiceSpy },
        { provide: DeviceIdService, useValue: deviceIdServiceSpy },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'poll-1' }) } },
        },
      ],
    }).compileComponents();
  });

  it('should render success confirmation after 201 vote', () => {
    pollServiceSpy.getPublicPoll.and.returnValue(of(samplePoll));
    pollServiceSpy.castVote.and.returnValue(
      of({ voteId: 'v1', pollId: 'poll-1', optionId: 'opt-1', castAt: '2026-11-27T20:01:00Z' }),
    );

    const fixture = TestBed.createComponent(VotePollComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const optionButtons = el.querySelectorAll('.vote-option-btn');
    (optionButtons[0] as HTMLButtonElement).click();
    fixture.detectChanges();

    const submitBtn = el.querySelector('.vote-submit-btn') as HTMLButtonElement;
    submitBtn.click();
    fixture.detectChanges();

    expect(pollServiceSpy.castVote).toHaveBeenCalledWith('poll-1', {
      optionId: 'opt-1',
      deviceId: 'device-uuid-1',
    });
    expect(el.textContent).toContain('¡Voto registrado!');
  });

  it('should handle 409 duplicate and 422 closed votes and 404 missing poll', () => {
    pollServiceSpy.getPublicPoll.and.returnValue(of(samplePoll));
    pollServiceSpy.castVote.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 409 })),
      throwError(() => new HttpErrorResponse({ status: 422 })),
    );

    const fixture1 = TestBed.createComponent(VotePollComponent);
    fixture1.detectChanges();
    const el1 = fixture1.nativeElement as HTMLElement;
    (el1.querySelector('.vote-option-btn') as HTMLButtonElement).click();
    fixture1.detectChanges();
    (el1.querySelector('.vote-submit-btn') as HTMLButtonElement).click();
    fixture1.detectChanges();
    expect(el1.textContent).toContain('Ya votaste en esta encuesta desde este dispositivo.');

    const fixture2 = TestBed.createComponent(VotePollComponent);
    fixture2.detectChanges();
    const el2 = fixture2.nativeElement as HTMLElement;
    (el2.querySelector('.vote-option-btn') as HTMLButtonElement).click();
    fixture2.detectChanges();
    (el2.querySelector('.vote-submit-btn') as HTMLButtonElement).click();
    fixture2.detectChanges();
    expect(el2.textContent).toContain('Esta encuesta ya está cerrada');

    pollServiceSpy.getPublicPoll.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    const fixture3 = TestBed.createComponent(VotePollComponent);
    fixture3.detectChanges();
    expect((fixture3.nativeElement as HTMLElement).textContent).toContain('Encuesta no encontrada');
  });
});
