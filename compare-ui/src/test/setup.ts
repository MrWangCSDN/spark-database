import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './server'

Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn()
  }))
})

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
