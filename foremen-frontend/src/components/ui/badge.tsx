import * as React from 'react'

import { cn } from '@/lib/utils'

export type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline'

const variantClasses: Record<BadgeVariant, string> = {
  default: 'border-transparent bg-primary text-primary-foreground shadow',
  secondary: 'border-transparent bg-muted text-muted-foreground',
  destructive:
    'border-transparent bg-destructive text-destructive-foreground shadow',
  outline: 'text-foreground',
}

export interface BadgeProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: BadgeVariant
}

function Badge({ className, variant = 'default', ...props }: BadgeProps) {
  return (
    <div
      className={cn(
        'inline-flex items-center rounded-md border px-2.5 py-0.5 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
        variantClasses[variant],
        className,
      )}
      {...props}
    />
  )
}

export { Badge }
