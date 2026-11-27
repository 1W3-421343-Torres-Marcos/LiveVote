import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorResponse } from '../models/auth.models';

export function extractApiErrorMessage(
  error: unknown,
  fallback: string,
  statusOverrides: Partial<Record<number, string>> = {},
): string {
  if (!(error instanceof HttpErrorResponse)) {
    return fallback;
  }

  const override = statusOverrides[error.status];
  if (override) {
    return override;
  }

  if (error.status === 429) {
    return 'Demasiados intentos en poco tiempo. Esperá unos segundos y volvé a probar.';
  }
  if (error.status === 503 || error.status === 0) {
    return 'El servicio no está disponible en este momento. Intentá nuevamente en unos instantes.';
  }

  const body = error.error as Partial<ApiErrorResponse> | null | undefined;
  if (body && typeof body.message === 'string' && body.message.trim().length > 0) {
    return body.message;
  }

  return fallback;
}
