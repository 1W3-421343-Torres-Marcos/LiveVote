import { TestBed } from '@angular/core/testing';
import { DeviceIdService } from './device-id.service';

describe('DeviceIdService', () => {
  let service: DeviceIdService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    service = TestBed.inject(DeviceIdService);
  });

  it('should generate and persist deviceId using crypto.randomUUID', () => {
    const uuidSpy = spyOn(crypto, 'randomUUID').and.returnValue(
      '11111111-2222-4333-8444-555555555555',
    );

    const first = service.getDeviceId();
    const second = service.getDeviceId();

    expect(first).toBe('11111111-2222-4333-8444-555555555555');
    expect(second).toBe(first);
    expect(localStorage.getItem('livevote_device_id')).toBe(first);
    expect(uuidSpy).toHaveBeenCalledTimes(1);
  });

  it('should reuse already stored deviceId from localStorage', () => {
    localStorage.setItem('livevote_device_id', 'existing-device-id');

    expect(service.getDeviceId()).toBe('existing-device-id');
  });
});
