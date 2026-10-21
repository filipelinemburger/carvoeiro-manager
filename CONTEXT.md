# Carvoeiro Manager

A Criciúma EC football-management game: the manager runs a club through seasons of
competitions, and watches their own club's fixtures play out minute by minute.

## Language

### Watching a match

**Minute**:
The unit of simulation. A match is 90 of them (plus stoppage and extra time), and the
manager advances through them one at a time.

**Event**:
A narratable incident attributed to a player within a minute — a goal, a booking, an
injury, a substitution. Most minutes have none.
_Avoid_: Incident, action

**Beat**:
One minute's worth of on-pitch movement. Every minute has a beat, including the many that
carry no event — a beat with no event still shows the shape of play for that minute.
_Avoid_: Play, tick, frame

**Choreography**:
The movement within a beat: where each player and the ball travel, derived from the
minute's simulated state rather than invented at render time. Two viewings of the same
match produce the same choreography.

**Revealed**:
How much of a match the manager has watched. A live match discloses itself one minute at
a time; anything past the revealed minute is simulated but not yet knowable.

### Shape

**Formation**:
The named shape a team lines up in (4-4-2, 4-3-3…), expressed as an ordered set of slots.

**Slot**:
One position within a formation, carrying both a place on the pitch and the detailed
positions that may legitimately fill it.

**Attack zone**:
Where a team tries to force the play — down the flanks, through the middle, or mixed.
A tactical intent, not a location on the pitch.
