import { Slider as SliderPrimitive } from "@base-ui/react/slider"

import { cn } from "@/lib/utils"

function Slider({
  className,
  // Belongs on the thumb, not on the root: the root is a plain div, the thumb is what
  // carries the slider role. Base UI forwards aria-labelledby from the root but not
  // aria-label, so a label passed to this wrapper would otherwise reach nothing.
  "aria-label": ariaLabel,
  ...props
}: SliderPrimitive.Root.Props<number>) {
  return (
    <SliderPrimitive.Root
      data-slot="slider"
      className={cn("relative w-full select-none", className)}
      {...props}
    >
      <SliderPrimitive.Control className="flex h-6 w-full items-center py-1.5">
        <SliderPrimitive.Track className="h-1 w-full rounded-full bg-muted">
          <SliderPrimitive.Indicator className="h-full rounded-full bg-primary" />
          <SliderPrimitive.Thumb
            aria-label={ariaLabel}
            className="size-3.5 rounded-full border border-primary bg-background shadow-sm transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50 data-disabled:pointer-events-none data-disabled:opacity-50"
          />
        </SliderPrimitive.Track>
      </SliderPrimitive.Control>
    </SliderPrimitive.Root>
  )
}

export { Slider }
