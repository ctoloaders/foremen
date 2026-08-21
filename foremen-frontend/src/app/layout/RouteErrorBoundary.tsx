import { Component, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'

interface RouteErrorBoundaryProps {
  children: ReactNode
}

interface RouteErrorBoundaryState {
  hasError: boolean
  error: Error | null
}

/**
 * Class-based error boundary that catches chunk load failures
 * (dynamic import errors) and displays retry UI.
 */
class RouteErrorBoundaryInner extends Component<
  RouteErrorBoundaryProps,
  RouteErrorBoundaryState
> {
  override state: RouteErrorBoundaryState = {
    hasError: false,
    error: null,
  }

  static getDerivedStateFromError(error: Error): RouteErrorBoundaryState {
    return { hasError: true, error }
  }

  private isChunkLoadError(error: Error | null): boolean {
    if (!error) return false
    const message = error.message.toLowerCase()
    return (
      message.includes('loading chunk') ||
      message.includes('failed to fetch dynamically imported module') ||
      message.includes('importing a module script failed') ||
      error.name === 'ChunkLoadError'
    )
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null })
  }

  override render() {
    if (this.state.hasError) {
      const isChunkError = this.isChunkLoadError(this.state.error)

      return (
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 p-6 text-center">
          <p className="text-lg text-muted-foreground">
            {isChunkError
              ? 'Nie udało się załadować strony'
              : 'Wystąpił nieoczekiwany błąd'}
          </p>
          <button
            type="button"
            onClick={this.handleRetry}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
          >
            Spróbuj ponownie
          </button>
        </div>
      )
    }

    return this.props.children
  }
}

/**
 * Wrapper functional component that resets the error boundary
 * on navigation (location change) by passing `location.key` as the React key.
 */
export function RouteErrorBoundary({ children }: RouteErrorBoundaryProps) {
  const location = useLocation()

  return (
    <RouteErrorBoundaryInner key={location.key}>
      {children}
    </RouteErrorBoundaryInner>
  )
}
