import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PollResponse } from '../../core/models/poll.models';
import { PollService } from '../../core/services/poll.service';
import { extractApiErrorMessage } from '../../core/utils/http-error.util';

@Component({
  selector: 'app-my-polls',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="page-container">
      <div class="header-row">
        <div>
          <h1 class="page-title">Mis encuestas</h1>
          <p class="muted-text page-subtitle">
            Creá encuestas, compartí el link de votación y proyectá los resultados en vivo.
          </p>
        </div>
        <a routerLink="/polls/new" class="btn btn-primary">+ Nueva encuesta</a>
      </div>

      @if (errorMessage()) {
        <div class="alert alert-error" role="alert">{{ errorMessage() }}</div>
      }

      @if (loading()) {
        <div class="card">Cargando tus encuestas...</div>
      } @else if (polls().length === 0) {
        <div class="card empty-state">
          <h2>Todavía no creaste ninguna encuesta</h2>
          <p class="muted-text">Creá tu primera encuesta con 2 a 6 opciones para empezar a recibir votos.</p>
          <a routerLink="/polls/new" class="btn btn-primary">Crear primera encuesta</a>
        </div>
      } @else {
        <div class="polls-grid">
          @for (poll of polls(); track poll.id) {
            <article class="card poll-card">
              <div class="poll-header">
                <h2 class="poll-title">{{ poll.title }}</h2>
                <span class="badge" [class.badge-open]="poll.status === 'OPEN'" [class.badge-closed]="poll.status === 'CLOSED'">
                  {{ poll.status === 'OPEN' ? 'ABIERTA' : 'CERRADA' }}
                </span>
              </div>

              <ul class="option-preview">
                @for (option of poll.options; track option.id) {
                  <li>{{ option.text }}</li>
                }
              </ul>

              <div class="poll-actions">
                <button
                  type="button"
                  class="btn btn-secondary"
                  (click)="copyVoteLink(poll.id)"
                >
                  {{ copiedPollId() === poll.id ? '¡Link copiado!' : 'Copiar link de votación' }}
                </button>

                <a [routerLink]="['/vote', poll.id]" class="btn btn-secondary">
                  Abrir votación
                </a>

                <a [routerLink]="['/results', poll.id]" class="btn btn-primary">
                  Resultados en vivo
                </a>

                @if (poll.status === 'OPEN') {
                  <button
                    type="button"
                    class="btn btn-danger"
                    [disabled]="closingPollId() === poll.id"
                    (click)="closePoll(poll.id)"
                  >
                    {{ closingPollId() === poll.id ? 'Cerrando...' : 'Cerrar encuesta' }}
                  </button>
                }
              </div>
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [
    `
      .header-row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 1.5rem;
      }
      .page-title {
        margin: 0 0 0.25rem;
        font-size: 1.7rem;
      }
      .page-subtitle {
        margin: 0;
      }
      .empty-state {
        text-align: center;
        padding: 2.5rem 1.5rem;
      }
      .polls-grid {
        display: grid;
        gap: 1rem;
      }
      .poll-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 0.75rem;
      }
      .poll-title {
        margin: 0;
        font-size: 1.2rem;
      }
      .option-preview {
        margin: 0.75rem 0 1.15rem;
        padding-left: 1.2rem;
        color: var(--text-muted);
        display: grid;
        gap: 0.25rem;
      }
      .poll-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.65rem;
      }
    `,
  ],
})
export class MyPollsComponent implements OnInit {
  private readonly pollService = inject(PollService);

  protected readonly polls = signal<PollResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly closingPollId = signal<string | null>(null);
  protected readonly copiedPollId = signal<string | null>(null);

  ngOnInit(): void {
    this.pollService.getMyPolls().subscribe({
      next: (polls) => {
        this.polls.set(polls);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.errorMessage.set(extractApiErrorMessage(err, 'No se pudieron cargar tus encuestas.'));
      },
    });
  }

  protected copyVoteLink(pollId: string): void {
    const voteUrl = `${window.location.origin}/vote/${pollId}`;
    this.copiedPollId.set(pollId);
    setTimeout(() => {
      if (this.copiedPollId() === pollId) {
        this.copiedPollId.set(null);
      }
    }, 2000);

    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(voteUrl).catch(() => undefined);
    }
  }

  protected closePoll(pollId: string): void {
    if (this.closingPollId() !== null) {
      return;
    }

    this.closingPollId.set(pollId);
    this.errorMessage.set(null);

    this.pollService.closePoll(pollId).subscribe({
      next: (updatedPoll) => {
        this.polls.update((items) =>
          items.map((item) => (item.id === pollId ? updatedPoll : item)),
        );
        this.closingPollId.set(null);
      },
      error: (err: unknown) => {
        this.closingPollId.set(null);
        this.errorMessage.set(extractApiErrorMessage(err, 'No se pudo cerrar la encuesta.'));
      },
    });
  }
}
