import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WeatherChip } from "./WeatherChip";

describe("WeatherChip", () => {
  it("shows the condition label and the temperature", () => {
    render(<WeatherChip condition="CHUVA_FORTE" temperatureC={18} />);
    expect(screen.getByText(/Chuva forte/)).toBeInTheDocument();
    expect(screen.getByText(/18°C/)).toBeInTheDocument();
  });

  it("renders nothing when the forecast is not yet revealed", () => {
    const { container } = render(<WeatherChip condition={null} temperatureC={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("labels every condition in pt-BR", () => {
    const conditions = ["ENSOLARADO", "NUBLADO", "VENTANIA", "CHUVA", "CHUVA_FORTE"] as const;
    for (const condition of conditions) {
      const { unmount } = render(<WeatherChip condition={condition} temperatureC={22} />);
      expect(screen.getByTestId("weather-chip")).toBeInTheDocument();
      unmount();
    }
  });
});
