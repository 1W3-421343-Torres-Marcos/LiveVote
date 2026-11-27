import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { extractApiErrorMessage } from '../../core/utils/http-error.util';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <section class="narrow-container">
      <div class="card">
        <h1 class="auth-title">Iniciar sesión</h1>
        <p class="muted-text auth-subtitle">
          Accedé a tus encuestas como presentador y proyectá resultados en vivo.
        </p>

        @if (errorMessage()) {
          <div class="alert alert-error" role="alert">{{ errorMessage() }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <div class="form-group">
            <label class="form-label" for="login-email">Email</label>
            <input
              id="login-email"
              type="email"
              class="form-input"
              formControlName="email"
              autocomplete="email"
              placeholder="presentador@livevote.local"
            />
            @if (form.controls.email.touched && form.controls.email.invalid) {
              <span class="field-error">Ingresá un email válido.</span>
            }
          </div>

          <div class="form-group">
            <label class="form-label" for="login-password">Contraseña</label>
            <input
              id="login-password"
              type="password"
              class="form-input"
              formControlName="password"
              autocomplete="current-password"
              placeholder="Mínimo 8 caracteres"
            />
            @if (form.controls.password.touched && form.controls.password.invalid) {
              <span class="field-error">La contraseña debe tener entre 8 y 72 caracteres.</span>
            }
          </div>

          <button type="submit" class="btn btn-primary btn-full" [disabled]="submitting()">
            {{ submitting() ? 'Ingresando...' : 'Ingresar' }}
          </button>
        </form>

        <p class="auth-footer muted-text">
          ¿Todavía no tenés cuenta? <a routerLink="/register">Registrate acá</a>
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
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

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
    this.authService.login({ email: email.trim(), password }).subscribe({
      next: () => {
        this.submitting.set(false);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/polls';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.errorMessage.set(
          extractApiErrorMessage(err, 'No se pudo iniciar sesión.', {
            401: 'Email o contraseña incorrectos.',
            400: 'Revisá el formato del email y la contraseña.',
          }),
        );
      },
    });
  }
}
