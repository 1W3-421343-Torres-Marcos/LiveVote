import { Component, inject, signal } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PollService } from '../../core/services/poll.service';
import { extractApiErrorMessage } from '../../core/utils/http-error.util';

@Component({
  selector: 'app-create-poll',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="narrow-container">
      <div class="card">
        <div class="header-line">
          <h1>Nueva encuesta</h1>
          <a routerLink="/polls" class="btn btn-secondary">Volver</a>
        </div>

        @if (errorMessage()) {
          <div class="alert alert-error" role="alert">{{ errorMessage() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="form-group">
            <label class="form-label" for="poll-title">Pregunta</label>
            <input
              id="poll-title"
              type="text"
              class="form-input"
              formControlName="title"
              placeholder="Ej: ¿Qué tema repasamos primero hoy?"
            />
            @if (form.controls.title.touched && form.controls.title.invalid) {
              <span class="field-error">Ingresá una pregunta (máximo 255 caracteres).</span>
            }
          </div>

          <div class="options-section" formArrayName="options">
            <div class="options-header">
              <span class="form-label">Opciones (entre {{ minOptions }} y {{ maxOptions }})</span>
              <button
                type="button"
                class="btn btn-secondary"
                [disabled]="optionsArray.length >= maxOptions"
                (click)="addOption()"
              >
                + Agregar opción
              </button>
            </div>

            @for (ctrl of optionsArray.controls; track ctrl; let i = $index) {
              <div class="option-row">
                <input
                  type="text"
                  class="form-input"
                  [formControlName]="i"
                  [placeholder]="'Opción ' + (i + 1)"
                  [attr.aria-label]="'Opción ' + (i + 1)"
                />
                <button
                  type="button"
                  class="btn btn-danger"
                  [disabled]="optionsArray.length <= minOptions"
                  (click)="removeOption(i)"
                >
                  Quitar
                </button>
              </div>
            }
          </div>

          <button type="submit" class="btn btn-primary btn-full" [disabled]="submitting()">
            {{ submitting() ? 'Creando encuesta...' : 'Crear encuesta' }}
          </button>
        </form>
      </div>
    </section>
  `,
  styles: [
    `
      .header-line {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 1.25rem;
      }
      .header-line h1 {
        margin: 0;
        font-size: 1.55rem;
      }
      .options-section {
        margin-bottom: 1.25rem;
      }
      .options-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.75rem;
        margin-bottom: 0.75rem;
      }
      .option-row {
        display: flex;
        gap: 0.5rem;
        margin-bottom: 0.65rem;
      }
    `,
  ],
})
export class CreatePollComponent {
  private readonly pollService = inject(PollService);
  private readonly router = inject(Router);

  protected readonly minOptions = 2;
  protected readonly maxOptions = 6;
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly optionsArray = new FormArray<FormControl<string>>([
    this.buildOptionControl(),
    this.buildOptionControl(),
  ]);

  protected readonly form = new FormGroup({
    title: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)],
    }),
    options: this.optionsArray,
  });

  addOption(): void {
    if (this.optionsArray.length < this.maxOptions) {
      this.optionsArray.push(this.buildOptionControl());
    }
  }

  removeOption(index: number): void {
    if (this.optionsArray.length > this.minOptions) {
      this.optionsArray.removeAt(index);
    }
  }

  submit(): void {
    const title = this.form.controls.title.value.trim();
    const cleanedOptions = this.optionsArray.controls.map((c) => c.value.trim());

    if (
      this.form.invalid ||
      title.length === 0 ||
      cleanedOptions.some((opt) => opt.length === 0) ||
      this.submitting()
    ) {
      this.form.markAllAsTouched();
      this.errorMessage.set('Completá la pregunta y todas las opciones sin dejar campos vacíos.');
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);

    this.pollService.createPoll({ title, options: cleanedOptions }).subscribe({
      next: () => {
        this.submitting.set(false);
        void this.router.navigate(['/polls']);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.errorMessage.set(extractApiErrorMessage(err, 'No se pudo crear la encuesta.'));
      },
    });
  }

  private buildOptionControl(): FormControl<string> {
    return new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(200)],
    });
  }
}
