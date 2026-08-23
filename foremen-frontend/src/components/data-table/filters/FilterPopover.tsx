import type { ReactNode } from 'react'

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover'

interface FilterPopoverProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  trigger: ReactNode
  children: ReactNode
}

export function FilterPopover({
  open,
  onOpenChange,
  trigger,
  children,
}: FilterPopoverProps) {
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent className="w-64 p-4 bg-background border border-border shadow-lg" align="start">
        {children}
      </PopoverContent>
    </Popover>
  )
}
