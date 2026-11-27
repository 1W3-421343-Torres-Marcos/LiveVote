import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const isApiRequest = req.url.startsWith('/api/');
  const isAuthEndpoint =
    req.url.startsWith('/api/auth/login') || req.url.startsWith('/api/auth/register');

  let outgoing = req;
  if (isApiRequest && !isAuthEndpoint) {
    const token = authService.getToken();
    if (token) {
      outgoing = req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`,
        },
      });
    }
  }

  return next(outgoing).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && isApiRequest && !isAuthEndpoint) {
        authService.clearSession();
        void router.navigate(['/login']);
      }
      return throwError(() => error);
    }),
  );
};
