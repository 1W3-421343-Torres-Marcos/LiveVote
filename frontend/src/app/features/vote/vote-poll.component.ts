import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PollResponse } from '../../core/models/poll.models';
import { DeviceIdService } from '../../core/services/device-id.service';
import { PollService } from '../../core/services/poll.service';
import { extractApiErrorMessage } from '../../core/utils/http-error.util';

type VoteViewState = 'idle' | 'success' | 'duplicate' | 'closed' | 'not-found' | 'error';

@Component({
  selector: 'app-vote-poll',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="narrow-container vote-wrapper">
      @if (loading()) {
        <div class="card">Cargando encuesta...</div>
      } @else if (voteState() === 'not-found') {
        <div class="card">
          <h1>Encuesta no encontrada</h1>
          <div class="alert alert-error" role="alert">
            No encontramos la encuesta solicitada o el enlace es inválido.
          </div>
        </div>
      } @else if (poll(); as currentPoll) {
        <div class="card vote-card">
          <div class="vote-header">
            <span
              class="badge"
              [class.badge-open]="currentPoll.status === 'OPEN'"
              [class.badge-closed]="currentPoll.status === 'CLOSED'"
            >
              {{ currentPoll.status === 'OPEN' ? 'ABIERTA' : 'CERRADA' }}
            </span>
          </div>

          <h1 class="vote-title">{{ currentPoll.title }}</h1>

          @if (voteState() === 'success') {
            <div class="alert alert-success" role="status">
              <strong>¡Voto registrado!</strong> Gracias por participar.
            </div>
            <a [routerLink]="['/results', pollId]" class="btn btn-primary btn-full">
              Ver resultados en vivo
            </a>
          } @else if (voteState() === 'duplicate') {
            <div class="alert alert-warning" role="status">
              Ya votaste en esta encuesta desde este dispositivo.
            </div>
            <a [routerLink]="['/results', pollId]" class="btn btn-primary btn-full">
              Ver resultados en vivo
            </a>
          } @else if (voteState() === 'closed') {
            <div class="alert alert-warning" role="status">
              Esta encuesta ya está cerrada y no admite más votos.
            </div>
            <a [routerLink]="['/results', pollId]" class="btn btn-primary btn-full">
              Ver resultados finales
            </a>
          } @else {
            @if (voteState() === 'error' && errorMessage()) {
              <div class="alert alert-error" role="alert">{{ errorMessage() }}</div>
            }

            <p class="muted-text vote-hint">Elegí una opción:</p>

            <div class="options-stack" role="radiogroup" [attr.aria-label]="currentPoll.title">
              @for (option of currentPoll.options; track option.id) {
                <button
                  type="button"
                  class="vote-option-btn"
                  [class.selected]="selectedOptionId() === option.id"
                  [disabled]="submitting()"
                  (click)="selectOption(option.id)"
                >
                  <span class="option-radio" aria-hidden="true"></span>
                  <span class="option-text">{{ option.text }}</span>
                </button>
              }
            </div>

            <button
              type="button"
              class="btn btn-primary btn-full vote-submit-btn"
              [disabled]="!selectedOptionId() || submitting()"
              (click)="submitVote()"
            >
              {{ submitting() ? 'Enviando voto...' : 'Enviar voto' }}
            </button>

            <div class="results-shortcut">
              <a [routerLink]="['/results', pollId]">Ver resultados en vivo</a>
            </div>
          }
        </div>
      }
    </section>
  `,
  styles: [
    `
      .vote-wrapper {
        margin-top: 1.25rem;
      }
      .vote-header {
        margin-bottom: 0.75rem;
      }
      .vote-title {
        margin: 0 0 1rem;
        font-size: 1.55rem;
        line-height: 1.25;
      }
      .vote-hint {
        margin: 0 0 0.85rem;
      }
      .options-stack {
        display: grid;
        gap: 0.75rem;
        margin-bottom: 1.25rem;
      }
      .vote-option-btn {
        width: 100%;
        min-height: 58px;
        padding: 0.9rem 1rem;
        border-radius: 12px;
        border: 2px solid var(--border);
        background: var(--surface);
        color: var(--text);
        display: flex;
        align-items: center;
        gap: 0.8rem;
        font-size: 1.05rem;
        font-weight: 600;
        text-align: left;
        cursor: pointer;
        transition: border-color 0.15s ease, background-color 0.15s ease;
      }
      .vote-option-btn:hover:not(:disabled) {
        border-color: #93c5fd;
        background: var(--surface-muted);
      }
      .vote-option-btn.selected {
        border-color: var(--primary);
        background: var(--primary-soft);
      }
      .option-radio {
        width: 20px;
        height: 20px;
        border-radius: 50%;
        border: 2px solid #94a3b8;
        flex-shrink: 0;
      }
      .vote-option-btn.selected .option-radio {
        border-color: var(--primary);
        background: radial-gradient(circle, var(--primary) 50%, transparent 56%);
      }
      .vote-submit-btn {
        font-size: 1.05rem;
        min-height: 52px;
      }
      .results-shortcut {
        margin-top: 1rem;
        text-align: center;
        font-size: 0.92rem;
      }
    `,
  ],
})
export class VotePollComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly pollService = inject(PollService);
  private readonly deviceIdService = inject(DeviceIdService);

  protected pollId = '';
  protected readonly loading = signal(true);
  protected readonly poll = signal<PollResponse | null>(null);
  protected readonly selectedOptionId = signal<string | null>(null);
  protected readonly submitting = signal(false);
  protected readonly voteState = signal<VoteViewState>('idle');
  protected readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.pollId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.pollId) {
      this.loading.set(false);
      this.voteState.set('not-found');
      return;
    }

    this.pollService.getPublicPoll(this.pollId).subscribe({
      next: (poll) => {
        this.poll.set(poll);
        this.loading.set(false);
        if (poll.status === 'CLOSED') {
          this.voteState.set('closed');
        }
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (err instanceof HttpErrorResponse && (err.status === 404 || err.status === 400)) {
          this.voteState.set('not-found');
          return;
        }
        this.voteState.set('error');
        this.errorMessage.set(extractApiErrorMessage(err, 'No se pudo cargar la encuesta.'));
      },
    });
  }

  protected selectOption(optionId: string): void {
    this.selectedOptionId.set(optionId);
  }

  protected submitVote(): void {
    const optionId = this.selectedOptionId();
    if (!optionId || this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);
    const deviceId = this.deviceIdService.getDeviceId();

    this.pollService.castVote(this.pollId, { optionId, deviceId }).subscribe({
      next: () => {
        this.submitting.set(false);
        this.voteState.set('success');
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        if (err instanceof HttpErrorResponse) {
          if (err.status === 409) {
            this.voteState.set('duplicate');
            return;
          }
          if (err.status === 422) {
            this.voteState.set('closed');
            return;
          }
          if (err.status === 404) {
            this.voteState.set('not-found');
            return;
          }
        }
        this.voteState.set('error');
        this.errorMessage.set(extractApiErrorMessage(err, 'No se pudo enviar tu voto.'));
      },
    });
  }
}
