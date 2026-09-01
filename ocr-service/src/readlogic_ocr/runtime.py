from dataclasses import dataclass


@dataclass
class RuntimeState:
    engine: str
    model: str
    ready: bool = False

    def mark_ready(self) -> None:
        self.ready = True

    def mark_not_ready(self) -> None:
        self.ready = False
