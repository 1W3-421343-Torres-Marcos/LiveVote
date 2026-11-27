import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AuthResponse, LoginRequest, RegisterRequest, StoredUser } from '../models/auth.models';

const TOKEN_STORAGE_KEY = 'livevote_jwt_token';
const USER_STORAGE_KEY = 'livevote_auth_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly userSignal = signal<StoredUser | null>(this.loadInitialUser());

  readonly currentUser = this.userSignal.asReadonly();

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/register', request)
      .pipe(tap((response) => this.persistSession(response)));
  }

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/login', request)
      .pipe(tap((response) => this.persistSession(response)));
  }

  getToken(): string | null {
    const token = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!token || token.trim().length === 0) {
      return null;
    }

    const tokenExpiry = this.extractJwtExpiryMs(token);
    if (tokenExpiry !== null && tokenExpiry <= Date.now()) {
      this.clearSession();
      return null;
    }

    const storedUser = this.readStoredUser();
    if (storedUser && storedUser.expiresAt <= Date.now()) {
      this.clearSession();
      return null;
    }

    return token;
  }

  isAuthenticated(): boolean {
    const valid = this.getToken() !== null;
    if (!valid && this.userSignal() !== null) {
      this.userSignal.set(null);
    }
    return valid;
  }

  clearSession(): void {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(USER_STORAGE_KEY);
    this.userSignal.set(null);
  }

  logout(): void {
    this.clearSession();
    void this.router.navigate(['/login']);
  }

  private persistSession(response: AuthResponse): void {
    const expiresAt =
      this.extractJwtExpiryMs(response.token) ?? Date.now() + response.expiresIn * 1000;
    const storedUser: StoredUser = {
      userId: response.userId,
      email: response.email,
      expiresAt,
    };
    localStorage.setItem(TOKEN_STORAGE_KEY, response.token);
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(storedUser));
    this.userSignal.set(storedUser);
  }

  private loadInitialUser(): StoredUser | null {
    const token = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!token || token.trim().length === 0) {
      localStorage.removeItem(USER_STORAGE_KEY);
      return null;
    }

    const tokenExpiry = this.extractJwtExpiryMs(token);
    if (tokenExpiry !== null && tokenExpiry <= Date.now()) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      localStorage.removeItem(USER_STORAGE_KEY);
      return null;
    }

    const storedUser = this.readStoredUser();
    if (storedUser && storedUser.expiresAt <= Date.now()) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      localStorage.removeItem(USER_STORAGE_KEY);
      return null;
    }

    return storedUser;
  }

  private readStoredUser(): StoredUser | null {
    const raw = localStorage.getItem(USER_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<StoredUser>;
      if (
        typeof parsed.userId === 'string' &&
        typeof parsed.email === 'string' &&
        typeof parsed.expiresAt === 'number'
      ) {
        return parsed as StoredUser;
      }
      return null;
    } catch {
      return null;
    }
  }

  private extractJwtExpiryMs(token: string): number | null {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return null;
    }
    try {
      const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
      const payload = JSON.parse(atob(padded)) as { exp?: unknown };
      return typeof payload.exp === 'number' ? payload.exp * 1000 : null;
    } catch {
      return null;
    }
  }
}
