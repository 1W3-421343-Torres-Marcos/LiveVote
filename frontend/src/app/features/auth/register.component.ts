import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { extractApiErrorMessage } from '../../core/utils/http-error.util';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="narrow-container">
      <div class="card">
        <h1 class="auth-title">Crear cuenta</h1>
        <p class="muted-text auth-subtitle">
          Creá encuestas en segundos y compartilas con tu audiencia en tiempo real.
        </p>

        @if (errorMessage()) {
          <div class="alert alert-error" role="alert">{{ errorMessage() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="form-group">
            <label class="form-label" for="register-email">Email</label>
            <input
              id="register-email"
              type="email"
              class="form-input"
              formControlName="email"
              autocomplete="email"
              placeholder="tu-email@ejemplo.com"
            />
            @if (form.controls.email.touched && form.controls.email.invalid) {
              <span class="field-error">Ingresá un email válido.</span>
            }
          </div>

          <div class="form-group">
            <label class="form-label" for="register-password">Contraseña</label>
            <input
              id="register-password"
              type="password"
              class="form-input"
              formControlName="password"
              autocomplete="new-password"
              placeholder="Mínimo 8 caracteres"
            />
            @if (form.controls.password.touched && form.controls.password.invalid) {
              <span class="field-error">Debe tener entre 8 y 72 caracteres.</span>
            }
          </div>

          <button type="submit" class="btn btn-primary btn-full" [disabled]="submitting()">
            {{ submitting() ? 'Creando cuenta...' : 'Registrarme' }}
          </button>
        </form>

        <p class="auth-footer muted-text">
          ¿Ya tenés cuenta? <a routerLink="/login">Iniciá sesión</a>
        </p>
      </div>
    </section>
  `,
  styles: [
    `
      .auth-title {
        margin: 0 0 0.35rem;
        font-size: 1.6rem;
      }
      .auth-subtitle {
        margin: 0 0 1.35rem;
        font-size: 0.95rem;
      }
      .auth-footer {
        margin: 1.25rem 0 0;
        text-align: center;
        font-size: 0.92rem;
      }
    `,
  ],
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);

    const { email, password } = this.form.getRawValue();
    this.authService.register({ email: email.trim(), password }).subscribe({
      next: () => {
        this.submitting.set(false);
        void this.router.navigate(['/polls']);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.errorMessage.set(
          extractApiErrorMessage(err, 'No se pudo completar el registro.', {
            409: 'Ya existe una cuenta registrada con ese email.',
            400: 'Revisá el formato del email y que la contraseña tenga al menos 8 caracteres.',
          }),
        );
      },
    });
  }
}
