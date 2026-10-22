import { Button } from '@cloud-notes/admin/components/ui/button';
import { Checkbox } from '@cloud-notes/admin/components/ui/checkbox';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@cloud-notes/admin/components/ui/popover';
import type { FeatureType } from '@cloud-notes/graphql';
import { useCallback } from 'react';

type FeatureFilterPopoverProps = {
  selectedFeatures: FeatureType[];
  availableFeatures: FeatureType[];
  onChange: (features: FeatureType[]) => void;
  align?: 'start' | 'center' | 'end';
  buttonLabel?: string;
  disabled?: boolean;
};

export const FeatureFilterPopover = ({
  selectedFeatures,
  availableFeatures,
  onChange,
  align = 'start',
  buttonLabel = 'Features',
  disabled = false,
}: FeatureFilterPopoverProps) => {
  const handleFeatureToggle = useCallback(
    (feature: FeatureType, checked: boolean) => {
      if (disabled) {
        return;
      }
      if (checked) {
        onChange([...new Set([...selectedFeatures, feature])]);
      } else {
        onChange(selectedFeatures.filter(enabled => enabled !== feature));
      }
    },
    [disabled, onChange, selectedFeatures]
  );

  const handleClearFeatures = useCallback(() => {
    if (disabled) {
      return;
    }
    onChange([]);
  }, [disabled, onChange]);

  return (
    <Popover open={disabled ? false : undefined}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className="h-8 px-2 lg:px-3 space-x-1"
          disabled={disabled}
        >
          <span>{buttonLabel}</span>
          {selectedFeatures.length > 0 ? (
            <span className="text-xs text-muted-foreground">
              ({selectedFeatures.length})
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align={align}
        className="w-[240px] p-2 flex flex-col gap-2"
      >
        <div className="text-xs font-medium px-1">Filter by feature</div>
        <div className="flex flex-col gap-1 max-h-64 overflow-auto">
          {availableFeatures.map(feature => (
            <label
              key={feature}
              className="flex items-center gap-2 px-1 py-1.5 cursor-pointer"
            >
              <Checkbox
                checked={selectedFeatures.includes(feature)}
                onCheckedChange={checked =>
                  handleFeatureToggle(feature, !!checked)
                }
                disabled={disabled}
              />
              <span className="text-sm truncate">{feature}</span>
            </label>
          ))}
        </div>
        <div className="flex justify-end gap-2 px-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleClearFeatures}
            disabled={disabled || selectedFeatures.length === 0}
          >
            Clear
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
};
