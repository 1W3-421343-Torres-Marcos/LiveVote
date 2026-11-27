import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard, guestGuard } from './auth.guard';

describe('authGuard and guestGuard', () => {
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('authGuard should redirect unauthenticated users to /login with returnUrl', () => {
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/polls/new' } as RouterStateSnapshot),
    );

    expect(result instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fpolls%2Fnew');
  });

  it('authGuard should allow authenticated users and guestGuard should redirect to /polls', () => {
    localStorage.setItem('livevote_jwt_token', 'valid-token');

    const authResult = TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/polls' } as RouterStateSnapshot),
    );
    expect(authResult).toBeTrue();

    const guestResult = TestBed.runInInjectionContext(() =>
      guestGuard({} as ActivatedRouteSnapshot, { url: '/login' } as RouterStateSnapshot),
    );
    expect(guestResult instanceof UrlTree).toBeTrue();
    expect(router.serializeUrl(guestResult as UrlTree)).toBe('/polls');
  });
});
