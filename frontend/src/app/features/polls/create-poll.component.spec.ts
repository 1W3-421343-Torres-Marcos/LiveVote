import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { PollService } from '../../core/services/poll.service';
import { CreatePollComponent } from './create-poll.component';

describe('CreatePollComponent', () => {
  let pollServiceSpy: jasmine.SpyObj<PollService>;
  let router: Router;

  beforeEach(async () => {
    pollServiceSpy = jasmine.createSpyObj<PollService>('PollService', ['createPoll']);
    await TestBed.configureTestingModule({
      imports: [CreatePollComponent],
      providers: [provideRouter([]), { provide: PollService, useValue: pollServiceSpy }],
    }).compileComponents();
    router = TestBed.inject(Router);
  });

  it('should enforce between 2 and 6 options and submit trimmed poll payload', () => {
    const fixture = TestBed.createComponent(CreatePollComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.option-row').length).toBe(2);

    // Cannot remove below 2 options
    component.removeOption(0);
    fixture.detectChanges();
    expect(el.querySelectorAll('.option-row').length).toBe(2);

    // Add up to 6 options, 7th attempt stays at 6
    for (let i = 0; i < 5; i++) {
      component.addOption();
    }
    fixture.detectChanges();
    expect(el.querySelectorAll('.option-row').length).toBe(6);

    // Reduce back to 2 and submit valid poll
    for (let i = 0; i < 4; i++) {
      component.removeOption(2);
    }
    fixture.detectChanges();

    const inputs = Array.from(el.querySelectorAll('input')) as HTMLInputElement[];
    inputs[0].value = '  ¿Mejor framework?  ';
    inputs[0].dispatchEvent(new Event('input'));
    inputs[1].value = ' Angular ';
    inputs[1].dispatchEvent(new Event('input'));
    inputs[2].value = ' Spring Boot ';
    inputs[2].dispatchEvent(new Event('input'));
    fixture.detectChanges();

    pollServiceSpy.createPoll.and.returnValue(
      of({
        id: 'p1',
        title: '¿Mejor framework?',
        status: 'OPEN',
        options: [],
        createdAt: '2026-11-27T20:00:00Z',
        closedAt: null,
      }),
    );
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    component.submit();

    expect(pollServiceSpy.createPoll).toHaveBeenCalledWith({
      title: '¿Mejor framework?',
      options: ['Angular', 'Spring Boot'],
    });
    expect(navigateSpy).toHaveBeenCalledWith(['/polls']);
  });
});
