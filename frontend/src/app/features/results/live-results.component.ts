import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { PollResultsResponse } from '../../core/models/results.models';
import { AuthService } from '../../core/services/auth.service';
import { ResultsService } from '../../core/services/results.service';

@Component({
  selector: 'app-live-results',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="projector-page">
      <div class="projector-inner">
        <div class="projector-toolbar">
          <div class="status-cluster">
            <a [routerLink]="backRoute()" class="toolbar-link">← Volver</a>

            @if (results(); as res) {
              <span
                class="projector-badge"
                [class.badge-live]="res.status === 'OPEN'"
                [class.badge-final]="res.status === 'CLOSED'"
              >
                {{ res.status === 'OPEN' ? 'EN VIVO' : 'CERRADA' }}
              </span>
            }

            <span class="sse-status" [class.reconnecting]="connectionState() === 'reconnecting'">
              @if (connectionState() === 'connected') {
                SSE conectado
              } @else if (connectionState() === 'reconnecting') {
                Reconectando SSE...
              } @else {
                Conectando...
              }
              @if (lastHeartbeat()) {
                <span> · Latido {{ lastHeartbeat() }}</span>
              }
            </span>
          </div>

          <div class="toolbar-actions">
            <button type="button" class="toolbar-btn" (click)="copyVoteLink()">
              {{ copiedLink() ? '¡Link copiado!' : 'Copiar link de votación' }}
            </button>
            <button type="button" class="toolbar-btn" (click)="toggleFullscreen()">
              Pantalla completa
            </button>
          </div>
        </div>

        @if (notFound()) {
          <div class="projector-panel">
            <h1>Encuesta no encontrada</h1>
            <p class="panel-muted">No existen resultados disponibles para el identificador solicitado.</p>
          </div>
        } @else if (errorMessage()) {
          <div class="projector-panel">
            <h1>Error en resultados</h1>
            <p class="panel-muted">{{ errorMessage() }}</p>
          </div>
        } @else if (results(); as res) {
          <div class="projector-header">
            <h1 class="projector-title">{{ res.title }}</h1>
            <div class="total-counter">
              <span class="total-number">{{ res.totalVotes }}</span>
              <span class="total-label">{{ res.totalVotes === 1 ? 'voto total' : 'votos totales' }}</span>
            </div>
          </div>

          <div class="bars-list">
            @for (option of res.options; track option.optionId) {
              @let pct = percentage(option.votes, res.totalVotes);
              <div class="bar-card">
                <div class="bar-label-row">
                  <span class="bar-option-text">{{ option.text }}</span>
                  <span class="bar-metrics">{{ option.votes }} ({{ pct }}%)</span>
                </div>
                <div class="bar-track" role="progressbar" [attr.aria-valuenow]="pct" aria-valuemin="0" aria-valuemax="100">
                  <div class="bar-fill" [style.width.%]="pct"></div>
                </div>
              </div>
            }
          </div>
        } @else {
          <div class="projector-panel">
            <h1>Conectando a resultados en vivo...</h1>
          </div>
        }
      </div>
    </section>
  `,
  styles: [
    `
      .projector-page {
        min-height: 100vh;
        background: radial-gradient(circle at top right, #1e293b 0%, #0b1120 65%);
        color: #f8fafc;
        padding: 1.5rem 1.25rem 3rem;
      }
      .projector-inner {
        max-width: 1100px;
        margin: 0 auto;
      }
      .projector-toolbar {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 2rem;
      }
      .status-cluster,
      .toolbar-actions {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.75rem;
      }
      .toolbar-link,
      .toolbar-btn {
        background: rgba(255, 255, 255, 0.08);
        color: #e2e8f0;
        border: 1px solid rgba(148, 163, 184, 0.25);
        border-radius: 10px;
        padding: 0.5rem 0.9rem;
        font-size: 0.9rem;
        font-weight: 600;
        cursor: pointer;
        text-decoration: none;
      }
      .toolbar-link:hover,
      .toolbar-btn:hover {
        background: rgba(255, 255, 255, 0.16);
      }
      .projector-badge {
        border-radius: 999px;
        padding: 0.3rem 0.85rem;
        font-size: 0.85rem;
        font-weight: 800;
        letter-spacing: 0.04em;
      }
      .badge-live {
        background: rgba(34, 197, 94, 0.2);
        color: #86efac;
        border: 1px solid rgba(34, 197, 94, 0.45);
      }
      .badge-final {
        background: rgba(148, 163, 184, 0.2);
        color: #cbd5e1;
        border: 1px solid rgba(148, 163, 184, 0.4);
      }
      .sse-status {
        font-size: 0.88rem;
        color: #93c5fd;
      }
      .sse-status.reconnecting {
        color: #fcd34d;
      }
      .projector-header {
        display: flex;
        flex-wrap: wrap;
        align-items: flex-end;
        justify-content: space-between;
        gap: 1.5rem;
        margin-bottom: 2.25rem;
      }
      .projector-title {
        margin: 0;
        font-size: clamp(1.85rem, 3.6vw, 3rem);
        line-height: 1.15;
        max-width: 780px;
      }
      .total-counter {
        background: rgba(30, 41, 59, 0.9);
        border: 1px solid rgba(148, 163, 184, 0.25);
        border-radius: 14px;
        padding: 0.85rem 1.35rem;
        text-align: right;
      }
      .total-number {
        display: block;
        font-size: clamp(2rem, 4vw, 3rem);
        font-weight: 800;
        color: #38bdf8;
        line-height: 1;
      }
      .total-label {
        font-size: 0.9rem;
        color: #cbd5e1;
      }
      .bars-list {
        display: grid;
        gap: 1.25rem;
      }
      .bar-card {
        background: rgba(15, 23, 42, 0.78);
        border: 1px solid rgba(148, 163, 184, 0.2);
        border-radius: 16px;
        padding: 1.25rem 1.4rem;
      }
      .bar-label-row {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 0.75rem;
      }
      .bar-option-text {
        font-size: clamp(1.15rem, 2.2vw, 1.65rem);
        font-weight: 700;
      }
      .bar-metrics {
        font-size: clamp(1.1rem, 2vw, 1.45rem);
        font-weight: 700;
        color: #7dd3fc;
        white-space: nowrap;
      }
      .bar-track {
        height: 22px;
        background: rgba(148, 163, 184, 0.18);
        border-radius: 999px;
        overflow: hidden;
      }
      .bar-fill {
        height: 100%;
        background: linear-gradient(90deg, #0ea5e9 0%, #3b82f6 100%);
        border-radius: 999px;
        transition: width 0.35s ease;
      }
      .projector-panel {
        background: rgba(15, 23, 42, 0.8);
        border: 1px solid rgba(148, 163, 184, 0.25);
        border-radius: 16px;
        padding: 2.5rem;
        text-align: center;
      }
      .panel-muted {
        color: #cbd5e1;
      }
    `,
  ],
})
export class LiveResultsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly resultsService = inject(ResultsService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private streamSubscription: Subscription | null = null;
  private probingMissing = false;

  protected pollId = '';
  protected readonly results = signal<PollResultsResponse | null>(null);
  protected readonly connectionState = signal<'connecting' | 'connected' | 'reconnecting'>('connecting');
  protected readonly lastHeartbeat = signal<string | null>(null);
  protected readonly notFound = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly copiedLink = signal(false);

  ngOnInit(): void {
    this.pollId = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.pollId) {
      this.notFound.set(true);
      return;
    }

    this.streamSubscription = this.resultsService
      .streamResults(this.pollId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => {
          if (event.type === 'results') {
            this.results.set(event.payload);
            this.connectionState.set('connected');
            this.notFound.set(false);
            this.errorMessage.set(null);
            return;
          }
          if (event.type === 'heartbeat') {
            this.connectionState.set('connected');
            this.lastHeartbeat.set(this.formatTime(event.payload.timestamp));
            return;
          }
          this.connectionState.set('reconnecting');
          if (!this.results() && !this.probingMissing) {
            this.probeMissingPoll();
          }
        },
        error: () => {
          this.connectionState.set('reconnecting');
        },
      });
  }

  protected percentage(votes: number, totalVotes: number): number {
    if (totalVotes <= 0) {
      return 0;
    }
    return Math.round((votes / totalVotes) * 100);
  }

  protected backRoute(): string[] {
    return this.authService.isAuthenticated() ? ['/polls'] : ['/vote', this.pollId];
  }

  protected copyVoteLink(): void {
    const url = `${window.location.origin}/vote/${this.pollId}`;
    this.copiedLink.set(true);
    setTimeout(() => this.copiedLink.set(false), 2000);

    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(url).catch(() => undefined);
    }
  }

  protected toggleFullscreen(): void {
    if (typeof document === 'undefined') {
      return;
    }
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => undefined);
      return;
    }
    if (typeof document.documentElement?.requestFullscreen === 'function') {
      void document.documentElement.requestFullscreen().catch(() => undefined);
    }
  }

  private probeMissingPoll(): void {
    this.probingMissing = true;
    this.resultsService.getResults(this.pollId).subscribe({
      next: (snapshot) => {
        this.probingMissing = false;
        if (!this.results()) {
          this.results.set(snapshot);
        }
      },
      error: (err: unknown) => {
        this.probingMissing = false;
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.notFound.set(true);
          this.streamSubscription?.unsubscribe();
          return;
        }
        if (err instanceof HttpErrorResponse && err.status === 400) {
          this.errorMessage.set('El identificador de encuesta no es válido.');
          this.streamSubscription?.unsubscribe();
        }
      },
    });
  }

  private formatTime(isoTimestamp: string): string {
    const parsed = new Date(isoTimestamp);
    if (Number.isNaN(parsed.getTime())) {
      return isoTimestamp;
    }
    return parsed.toLocaleTimeString('es-AR', { hour12: false });
  }
}
