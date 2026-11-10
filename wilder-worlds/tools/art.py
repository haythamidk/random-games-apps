"""Every texture in Wilder Worlds is drawn here from scratch.

Nothing is copied or recoloured from the game's own art: block textures are
built from noise and simple rules, item icons from hand-placed shapes, and the
armour sheets are painted straight onto the armour model's layout.
"""
import random

from PIL import Image, ImageDraw

CLEAR = (0, 0, 0, 0)


def rgba(c, a=255):
    return (c[0], c[1], c[2], a)


def mixc(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def value_noise(r, size=16, cell=4):
    """Smooth tiling noise in 0..1, so tones form clumps instead of static."""
    n = size // cell
    grid = [[r.random() for _ in range(n)] for _ in range(n)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            gx, gy = x / cell, y / cell
            x0, y0 = int(gx) % n, int(gy) % n
            x1, y1 = (x0 + 1) % n, (y0 + 1) % n
            fx, fy = gx - int(gx), gy - int(gy)
            top = grid[y0][x0] * (1 - fx) + grid[y0][x1] * fx
            bot = grid[y1][x0] * (1 - fx) + grid[y1][x1] * fx
            out[y][x] = top * (1 - fy) + bot * fy
    return out


def ramp(palette, t):
    """Pick from a dark-to-light palette with t in 0..1."""
    return palette[max(0, min(len(palette) - 1, int(t * len(palette))))]


def grainy(seed, palette, cell=4, jitter=0.35):
    r = random.Random(seed)
    base = value_noise(r, cell=cell)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            t = base[y][x] * (1 - jitter) + r.random() * jitter
            img.putpixel((x, y), rgba(ramp(palette, t)))
    return img


# ---------------------------------------------------------------------------
# Wood
# ---------------------------------------------------------------------------
def bark(seed, palette, groove, groove_every):
    """Vertical bark: long fibrous strips split by dark grooves that wander."""
    r = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    offsets = [r.randint(0, 2) for _ in range(16)]
    tone = [r.random() for _ in range(16)]
    for x in range(16):
        streak = r.random()
        for y in range(16):
            if r.random() < 0.18:
                streak = r.random()
            is_groove = (x + offsets[y // 5]) % groove_every == 0
            if is_groove and r.random() < 0.85:
                colour = groove
            else:
                colour = ramp(palette, tone[x] * 0.55 + streak * 0.45)
            img.putpixel((x, y), rgba(colour))
    return img


def log_top(seed, bark_colour, rings):
    """End grain: a bark rim around uneven growth rings."""
    r = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) + (r.random() - 0.5) * 0.9
            if d > 6.6:
                colour = mixc(bark_colour, (0, 0, 0), r.random() * 0.25)
            else:
                colour = rings[int(d * 1.15) % len(rings)]
                colour = mixc(colour, (255, 255, 255), r.random() * 0.06)
            img.putpixel((x, y), rgba(colour))
    return img


def planks(seed, palette, seam):
    """Four boards with staggered joints and a soft grain."""
    r = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    for board in range(4):
        joint = r.randint(2, 13)
        shade = r.random() * 0.4
        for y in range(board * 4, board * 4 + 4):
            grain = r.random()
            for x in range(16):
                if r.random() < 0.3:
                    grain = r.random()
                colour = ramp(palette, shade + grain * 0.6)
                if y % 4 == 3 or x == joint:
                    colour = seam
                img.putpixel((x, y), rgba(colour))
    return img


def leaves(seed, palette, holes, stroke):
    """Cutout foliage. stroke is the (dx, dy) lean of each little leaf."""
    r = random.Random(seed)
    img = Image.new("RGBA", (16, 16), CLEAR)
    depth = value_noise(r, cell=4)
    for y in range(16):
        for x in range(16):
            if depth[y][x] * 0.6 + r.random() * 0.4 > holes:
                img.putpixel((x, y), rgba(ramp(palette, depth[y][x] * 0.5 + r.random() * 0.3)))
    for _ in range(26):   # lighter leaves on top, so it reads as layers
        x, y = r.randrange(16), r.randrange(16)
        for i in range(r.randint(2, 3)):
            px, py = (x + stroke[0] * i) % 16, (y + stroke[1] * i) % 16
            img.putpixel((px, py), rgba(palette[-1] if i else palette[-2]))
    return img


# ---------------------------------------------------------------------------
# Stone, ground and ores
# ---------------------------------------------------------------------------
STONE = [(104, 104, 108), (118, 118, 122), (130, 130, 134), (142, 142, 146)]
SLATE = [(52, 52, 58), (62, 62, 70), (72, 72, 80), (84, 84, 92)]
ASH = [(52, 50, 54), (62, 60, 64), (72, 69, 72), (86, 82, 84)]
PUMICE = [(150, 146, 140), (168, 164, 158), (184, 180, 172), (198, 194, 186)]
SALT = [(222, 228, 234), (234, 238, 242), (244, 246, 248), (252, 252, 252)]
EMBER = [(150, 40, 24), (214, 84, 30), (246, 142, 44), (255, 214, 110)]
ROSE = [(176, 78, 110), (218, 112, 142), (242, 158, 178), (255, 214, 222)]


def stone(seed, palette=STONE, layered=False):
    img = grainy(seed, palette, cell=4, jitter=0.45)
    if layered:   # deepslate-like: faint horizontal bedding
        r = random.Random(seed + 1)
        for y in (3, 8, 12):
            for x in range(16):
                if r.random() < 0.7:
                    img.putpixel((x, (y + r.randint(0, 1)) % 16), rgba(palette[0]))
    return img


def pumice(seed):
    img = grainy(seed, PUMICE, cell=4, jitter=0.3)
    r = random.Random(seed + 7)
    for _ in range(22):   # gas pores
        x, y = r.randrange(16), r.randrange(16)
        img.putpixel((x, y), rgba((112, 108, 104)))
        if r.random() < 0.4:
            img.putpixel(((x + 1) % 16, y), rgba((128, 124, 118)))
    return img


def ash(seed):
    img = grainy(seed, ASH, cell=8, jitter=0.6)
    r = random.Random(seed + 3)
    for _ in range(9):
        img.putpixel((r.randrange(16), r.randrange(16)), rgba((112, 106, 104)))
    return img


def salt(seed):
    img = grainy(seed, SALT, cell=4, jitter=0.25)
    r = random.Random(seed + 5)
    for _ in range(6):   # crystal facets: short diagonal glints with a blue shadow
        x, y = r.randrange(16), r.randrange(16)
        for i in range(r.randint(2, 4)):
            img.putpixel(((x + i) % 16, (y + i) % 16), rgba((255, 255, 255)))
            img.putpixel(((x + i) % 16, (y + i + 1) % 16), rgba((198, 210, 224)))
    return img


def bricks(base, mortar):
    """Lays a running bond over any base texture."""
    img = base.copy()
    for y in range(16):
        row = y // 4
        for x in range(16):
            seam = y % 4 == 3 or (x + (4 if row % 2 else 0)) % 8 == 7
            if seam:
                img.putpixel((x, y), rgba(mortar))
            elif y % 4 == 0:
                img.putpixel((x, y), rgba(mixc(img.getpixel((x, y))[:3], (255, 255, 255), 0.18)))
    return img


def nodules(img, seed, palette, count, rim=None):
    """Scatters ore lumps: a bright heart, cooler edge, optional dark rim."""
    r = random.Random(seed)
    spots = []
    while len(spots) < count:
        x, y = r.randint(1, 13), r.randint(1, 13)
        if all(abs(x - a) + abs(y - b) > 4 for a, b in spots):
            spots.append((x, y))
    for x, y in spots:
        shape = [(0, 0), (1, 0), (0, 1), (1, 1)]
        shape += r.sample([(-1, 0), (2, 1), (0, -1), (1, 2), (2, 0), (-1, 1)], r.randint(1, 3))
        if rim:
            for dx, dy in shape:
                for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    img.putpixel(((x + dx + ox) % 16, (y + dy + oy) % 16), rgba(rim))
        for i, (dx, dy) in enumerate(shape):
            tone = palette[3] if i == 0 else palette[2] if i < 3 else palette[1]
            img.putpixel(((x + dx) % 16, (y + dy) % 16), rgba(tone))
    return img


def crystals(img, seed, palette, count):
    """Upright shards, lit on the left, for the rose salt."""
    r = random.Random(seed)
    for _ in range(count):
        x, base, height = r.randint(1, 13), r.randint(6, 15), r.randint(3, 6)
        for i in range(height):
            y = base - i
            if y < 0:
                break
            img.putpixel((x, y), rgba(palette[3] if i == height - 1 else palette[2]))
            if i < height - 1:
                img.putpixel((x + 1, y), rgba(palette[1] if i else palette[0]))
    return img


def metal_block(seed):
    """Forged emberite: dark plate, bevelled edge, glowing cracks."""
    r = random.Random(seed)
    plate = [(44, 38, 44), (54, 46, 52), (64, 56, 62)]
    img = grainy(seed, plate, cell=8, jitter=0.3)
    for i in range(16):
        img.putpixel((i, 0), rgba((92, 82, 88)))
        img.putpixel((0, i), rgba((92, 82, 88)))
        img.putpixel((i, 15), rgba((28, 24, 28)))
        img.putpixel((15, i), rgba((28, 24, 28)))
    for _ in range(3):   # a crack is a random walk that glows hotter in the middle
        x, y = r.randint(3, 12), r.randint(2, 4)
        for step in range(r.randint(7, 10)):
            if 1 <= x <= 14 and 1 <= y <= 14:
                img.putpixel((x, y), rgba(EMBER[3] if 2 < step < 6 else EMBER[2] if step else EMBER[1]))
            x += r.choice((-1, 0, 0, 1))
            y += 1
    return img


def lamp():
    """Ember lamp: an iron cage around a core that is brightest in the middle."""
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 7.5
            img.putpixel((x, y), rgba(mixc(EMBER[3], EMBER[1], d ** 1.5)))
    frame, shine = (40, 34, 38), (84, 74, 80)
    for i in range(16):
        for edge in (0, 15):
            img.putpixel((i, edge), rgba(frame))
            img.putpixel((edge, i), rgba(frame))
    for i in range(1, 15):   # cross bars, with one lit edge
        img.putpixel((i, 5), rgba(frame))
        img.putpixel((i, 10), rgba(frame))
        img.putpixel((5, i), rgba(frame))
        img.putpixel((10, i), rgba(frame))
    for i in range(1, 15):
        img.putpixel((i, 1), rgba(shine))
        img.putpixel((1, i), rgba(shine))
    return img


# ---------------------------------------------------------------------------
# Sprites: plants and item icons
# ---------------------------------------------------------------------------
OUTLINE = (30, 22, 26)


def finish(img, outline=OUTLINE, shade=True):
    """Lights the top-left edges, darkens the bottom-right, then outlines."""
    src = img.copy()
    w, h = img.size

    def solid(x, y):
        return 0 <= x < w and 0 <= y < h and src.getpixel((x, y))[3] > 0

    for y in range(h):
        for x in range(w):
            if not solid(x, y):
                continue
            c = src.getpixel((x, y))[:3]
            if shade and (not solid(x - 1, y) or not solid(x, y - 1)):
                c = mixc(c, (255, 255, 255), 0.22)
            elif shade and (not solid(x + 1, y) or not solid(x, y + 1)):
                c = mixc(c, (0, 0, 0), 0.28)
            img.putpixel((x, y), rgba(c))
    if outline:
        for y in range(h):
            for x in range(w):
                if not solid(x, y) and (solid(x - 1, y) or solid(x + 1, y)
                                        or solid(x, y - 1) or solid(x, y + 1)):
                    img.putpixel((x, y), rgba(outline))
    return img


class Sprite:
    def __init__(self, size=16):
        self.img = Image.new("RGBA", (size, size), CLEAR)
        self.d = ImageDraw.Draw(self.img)
        self.glow = []

    def poly(self, pts, c):
        self.d.polygon(pts, fill=rgba(c))
        return self

    def line(self, pts, c, width=1):
        self.d.line(pts, fill=rgba(c), width=width)
        return self

    def dots(self, pts, c):
        for p in pts:
            self.img.putpixel(p, rgba(c))
        return self

    def hot(self, pts, c):
        """Pixels drawn after shading, so glowing parts stay pure."""
        self.glow += [(p, c) for p in pts]
        return self

    def done(self, outline=OUTLINE, shade=True):
        finish(self.img, outline, shade)
        for p, c in self.glow:
            self.img.putpixel(p, rgba(c))
        return self.img


STEEL = (118, 104, 112)
STEEL_HI = (166, 152, 158)
STEEL_LO = (74, 62, 70)
GRIP = (88, 58, 40)
WRAP = (156, 116, 74)


def handle(s, a, b):
    """Charred wooden haft with two leather wraps and an ember pommel."""
    s.line([a, b], GRIP, 2)
    mx, my = (a[0] + b[0]) // 2, (a[1] + b[1]) // 2
    s.dots([(mx, my), (mx - 2, my + 2)], WRAP)
    s.hot([(a[0], a[1])], EMBER[2])
    return s


def line_points(a, b):
    n = max(abs(b[0] - a[0]), abs(b[1] - a[1]))
    return [(round(a[0] + (b[0] - a[0]) * i / n), round(a[1] + (b[1] - a[1]) * i / n))
            for i in range(n + 1)]


def icon_sword():
    """A clipped falchion: the blade widens towards the tip, then cuts back."""
    s = Sprite()
    handle(s, (2, 13), (5, 10))
    s.line([(4, 8), (7, 11)], STEEL_LO, 1)                     # guard
    s.poly([(6, 9), (5, 8), (9, 3), (13, 1), (14, 2), (14, 5), (8, 10)], STEEL)
    s.line([(6, 8), (12, 2)], STEEL_HI)                        # spine
    s.hot(line_points((7, 9), (12, 4)), EMBER[2])              # heated edge
    s.hot([(13, 3), (13, 2)], EMBER[3])
    return s.done()


def icon_pickaxe():
    """One long spike, one square hammer face."""
    s = Sprite()
    handle(s, (2, 13), (9, 6))
    s.line([(3, 5), (6, 3), (9, 3), (12, 5)], STEEL, 2)        # arched head
    s.poly([(2, 6), (4, 3), (5, 5)], STEEL_HI)                 # spike
    s.poly([(11, 5), (14, 6), (13, 10), (10, 8)], STEEL)       # hammer block
    s.hot([(8, 3), (9, 4), (12, 8)], EMBER[2])
    s.hot([(12, 7)], EMBER[3])
    return s.done()


def icon_axe():
    """Bearded axe: the lower horn of the blade hangs below the haft."""
    s = Sprite()
    handle(s, (2, 13), (10, 5))
    s.poly([(9, 4), (11, 6), (9, 9), (5, 10), (3, 8), (4, 4), (7, 2)], STEEL)
    s.line([(4, 5), (4, 8), (5, 9)], STEEL_HI)                 # cutting edge
    s.hot([(8, 5), (7, 6), (8, 7)], EMBER[2])
    s.hot([(7, 5)], EMBER[3])
    return s.done()


def icon_shovel():
    """Leaf-shaped spade with a raised rib."""
    s = Sprite()
    handle(s, (2, 13), (8, 7))
    s.poly([(8, 8), (7, 5), (10, 2), (14, 1), (13, 5), (10, 8)], STEEL)
    s.line([(9, 6), (12, 3)], STEEL_HI)
    s.hot([(8, 7), (9, 7)], EMBER[2])
    return s.done()


def icon_hoe():
    """A sickle: the blade hooks back over the haft."""
    s = Sprite()
    handle(s, (2, 13), (10, 5))
    s.line([(10, 5), (11, 2), (8, 1), (5, 2), (3, 5)], STEEL, 2)
    s.line([(9, 2), (6, 2), (4, 4)], STEEL_HI)
    s.hot([(10, 4), (10, 3)], EMBER[2])
    return s.done()


PLATE = (70, 60, 68)
PLATE_HI = (112, 98, 106)


def icon_helmet():
    """Open-faced helm with a raised crest and a glowing brow line."""
    s = Sprite()
    s.poly([(3, 12), (3, 6), (5, 3), (10, 3), (12, 6), (12, 12), (10, 12), (10, 8), (5, 8), (5, 12)], PLATE)
    s.line([(7, 1), (8, 1), (8, 6), (7, 6), (7, 1)], PLATE_HI)   # crest
    s.line([(7, 8), (7, 10)], PLATE_HI)                           # nose guard
    s.hot([(5, 7), (6, 7), (9, 7), (10, 7)], EMBER[2])
    return s.done()


def icon_chestplate():
    """Layered pauldrons over a breastplate with an ember heart."""
    s = Sprite()
    s.poly([(4, 4), (11, 4), (11, 13), (4, 13)], PLATE)
    s.poly([(1, 3), (5, 2), (5, 6), (2, 7)], PLATE_HI)
    s.poly([(14, 3), (10, 2), (10, 6), (13, 7)], PLATE_HI)
    s.dots([(6, 3), (7, 4), (8, 4), (9, 3)], CLEAR[:3])
    s.line([(5, 10), (10, 10)], STEEL_LO)
    s.line([(5, 12), (10, 12)], STEEL_LO)
    s.hot([(7, 7), (8, 7), (7, 8), (8, 8)], EMBER[2])
    s.hot([(7, 7)], EMBER[3])
    return s.done()


def icon_leggings():
    s = Sprite()
    s.poly([(4, 2), (11, 2), (11, 5), (4, 5)], PLATE)             # hips
    s.poly([(4, 5), (6, 5), (6, 13), (4, 13)], PLATE)             # legs, with a gap
    s.poly([(9, 5), (11, 5), (11, 13), (9, 13)], PLATE)
    s.line([(4, 3), (11, 3)], PLATE_HI)                           # belt
    s.line([(4, 9), (6, 9)], PLATE_HI)                            # knee plates
    s.line([(9, 9), (11, 9)], PLATE_HI)
    s.hot([(7, 3), (8, 3)], EMBER[2])
    s.hot([(5, 8), (10, 8)], EMBER[1])
    return s.done()


def icon_boots():
    s = Sprite()
    s.poly([(2, 4), (5, 4), (5, 10), (7, 11), (7, 13), (2, 13)], PLATE)
    s.poly([(9, 4), (12, 4), (12, 10), (14, 11), (14, 13), (9, 13)], PLATE)
    s.line([(2, 5), (5, 5)], PLATE_HI)
    s.line([(9, 5), (12, 5)], PLATE_HI)
    s.hot([(3, 8), (4, 8), (10, 8), (11, 8)], EMBER[2])
    return s.done()


def icon_raw():
    """A broken lump of ore: grey rind, hot seams."""
    s = Sprite()
    s.poly([(3, 9), (4, 5), (7, 3), (11, 4), (13, 8), (11, 12), (6, 13)], (96, 90, 94))
    s.dots([(6, 6), (10, 9), (8, 11), (5, 10)], (70, 64, 68))
    s.hot([(7, 5), (8, 6), (9, 6), (6, 8), (7, 9), (10, 10), (9, 11)], EMBER[2])
    s.hot([(8, 5), (7, 8)], EMBER[3])
    s.hot([(11, 7), (5, 9)], EMBER[1])
    return s.done()


def icon_ingot():
    """A six-sided billet that still glows along its centre."""
    s = Sprite()
    s.poly([(2, 9), (5, 6), (12, 4), (14, 6), (11, 9), (4, 11)], STEEL_LO)
    s.poly([(5, 6), (12, 4), (14, 6), (7, 8)], STEEL)
    s.hot(line_points((5, 9), (11, 7)), EMBER[2])
    s.hot([(8, 8), (9, 8)], EMBER[3])
    return s.done()


def icon_salt():
    """Three leaning rose salt crystals."""
    s = Sprite()
    s.poly([(3, 13), (4, 6), (6, 4), (7, 13)], ROSE[1])
    s.poly([(7, 13), (8, 3), (10, 2), (11, 13)], ROSE[2])
    s.poly([(10, 13), (12, 7), (13, 8), (13, 13)], ROSE[1])
    s.line([(9, 4), (9, 11)], ROSE[3])
    s.dots([(5, 7), (5, 8)], ROSE[3])
    return s.done(outline=(96, 40, 62))


def icon_cured():
    """Two strips of cured meat tied with twine."""
    s = Sprite()
    meat, fat = (132, 44, 40), (214, 150, 118)
    s.poly([(2, 5), (5, 3), (13, 9), (11, 12)], meat)
    s.poly([(4, 10), (6, 8), (13, 12), (11, 14)], (112, 36, 34))
    s.line([(4, 5), (11, 10)], fat)
    s.line([(6, 10), (11, 13)], fat)
    s.line([(9, 6), (7, 12)], (226, 206, 160))                  # twine
    s.dots([(12, 10), (5, 4)], (166, 62, 52))
    return s.done(outline=(50, 18, 18))


def plant_lavender():
    """Three flower spikes on thin stems, for the cross-shaped plant model."""
    s = Sprite()
    stem, leaf = (86, 124, 64), (112, 150, 78)
    bud = [(118, 84, 176), (146, 108, 204), (180, 146, 230)]
    for x, top in ((4, 5), (8, 2), (12, 6)):
        s.line([(x, 15), (x, top + 4)], stem)
        for i in range(5):
            y = top + i
            s.dots([(x, y)], bud[1])
            s.dots([(x - 1 if i % 2 else x + 1, y)], bud[2] if i < 2 else bud[0])
        s.dots([(x, top - 1)], bud[2])
    s.dots([(5, 13), (7, 12), (9, 13), (11, 12), (3, 14), (13, 14)], leaf)
    return s.done(outline=None, shade=False)


def plant_ember_shrub():
    """A charred, leafless bush whose buds still smoulder."""
    s = Sprite()
    twig = (58, 44, 44)
    for path in ([(8, 15), (8, 10), (5, 6), (4, 3)], [(8, 11), (11, 7), (12, 4)],
                 [(7, 12), (3, 10), (2, 8)], [(9, 12), (13, 11), (14, 9)], [(6, 8), (8, 5), (8, 3)]):
        s.line(path, twig)
    s.hot([(4, 2), (12, 3), (2, 7), (14, 8), (8, 2)], EMBER[3])
    s.hot([(5, 3), (11, 4), (3, 8), (13, 9), (7, 3), (9, 6), (5, 7)], EMBER[2])
    return s.done(outline=None, shade=False)


# ---------------------------------------------------------------------------
# Armour sheets (64x32, the layout the game's armour model reads)
# ---------------------------------------------------------------------------
def faces(u, v, w, h, d):
    """Texture rectangles of a w*h*d box whose strip starts at (u, v)."""
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + d + w + d, v + d, w, h),
    }


def plate(img, rect, seed, rows=None):
    """Fills part of a face with riveted plate: lit top edge, dark bottom edge."""
    x0, y0, w, h = rect
    r = random.Random(seed)
    first, last = rows if rows else (0, h - 1)
    for y in range(first, last + 1):
        for x in range(w):
            c = mixc(PLATE, (0, 0, 0), r.random() * 0.12)
            if y == first or x == 0:
                c = mixc(PLATE_HI, PLATE, 0.3)
            if y == last or x == w - 1:
                c = STEEL_LO if y != first else c
            img.putpixel((x0 + x, y0 + y), rgba(c))


def put(img, rect, pts, c):
    for x, y in pts:
        img.putpixel((rect[0] + x, rect[1] + y), rgba(c) if c else CLEAR)


def armour_layer_1():
    """Helmet, chestplate and boots."""
    img = Image.new("RGBA", (64, 32), CLEAR)
    head = faces(0, 0, 8, 8, 8)
    for i, side in enumerate(("top", "right", "front", "left", "back")):
        plate(img, head[side], 20 + i)
    # open face: brow stays, eyes and mouth are cut out around a nose guard
    put(img, head["front"], [(x, y) for y in range(3, 8) for x in range(1, 7)], None)
    put(img, head["front"], [(3, y) for y in range(3, 6)] + [(4, y) for y in range(3, 6)], PLATE_HI)
    put(img, head["front"], [(x, 2) for x in range(1, 7)], EMBER[2])          # brow glow
    put(img, head["front"], [(1, 6), (1, 7), (6, 6), (6, 7)], PLATE)          # cheek guards
    for side in ("top", "back"):                                              # crest
        put(img, head[side], [(3, y) for y in range(8)] + [(4, y) for y in range(8)], PLATE_HI)
    put(img, head["top"], [(3, 7), (4, 7)], EMBER[1])
    for side in ("right", "left"):                                            # ear vents
        put(img, head[side], [(3, 4), (4, 4), (3, 5), (4, 5)], STEEL_LO)

    body = faces(16, 16, 8, 12, 4)
    for i, side in enumerate(("top", "right", "front", "left", "back")):
        plate(img, body[side], 40 + i)
    put(img, body["front"], [(3, 3), (4, 3), (3, 4), (4, 4)], EMBER[2])       # ember heart
    put(img, body["front"], [(3, 3)], EMBER[3])
    put(img, body["front"], [(2, 2), (5, 2), (2, 5), (5, 5), (1, 1), (6, 1)], PLATE_HI)
    for y in (7, 9):                                                          # belly bands
        put(img, body["front"], [(x, y) for x in range(1, 7)], STEEL_LO)
        put(img, body["back"], [(x, y) for x in range(1, 7)], STEEL_LO)
    put(img, body["back"], [(3, y) for y in range(1, 6)] + [(4, y) for y in range(1, 6)], PLATE_HI)

    arm = faces(40, 16, 4, 12, 4)
    for i, side in enumerate(("top", "right", "front", "left", "back")):
        plate(img, arm[side], 60 + i, rows=None if side == "top" else (0, 5))  # pauldron
        if side != "top":
            plate(img, arm[side], 70 + i, rows=(9, 11))                        # bracer
            put(img, arm[side], [(x, 9) for x in range(4)], EMBER[1])
            put(img, arm[side], [(x, 3) for x in range(4)], STEEL_LO)

    boot = faces(0, 16, 4, 12, 4)
    for i, side in enumerate(("right", "front", "left", "back")):
        plate(img, boot[side], 80 + i, rows=(7, 11))
        put(img, boot[side], [(x, 7) for x in range(4)], EMBER[1])
        put(img, boot[side], [(x, 11) for x in range(4)], (34, 28, 32))
    plate(img, boot["bottom"], 90)
    put(img, boot["front"], [(1, 9), (2, 9)], PLATE_HI)
    return img


def armour_layer_2():
    """Leggings: a belt on the body box, plates down the legs."""
    img = Image.new("RGBA", (64, 32), CLEAR)
    body = faces(16, 16, 8, 12, 4)
    for i, side in enumerate(("right", "front", "left", "back")):
        plate(img, body[side], 100 + i, rows=(7, 11))
    put(img, body["front"], [(3, 8), (4, 8)], EMBER[2])                       # buckle
    put(img, body["front"], [(2, 8), (5, 8)], PLATE_HI)
    leg = faces(0, 16, 4, 12, 4)
    for i, side in enumerate(("top", "right", "front", "left", "back")):
        plate(img, leg[side], 110 + i, rows=None if side == "top" else (0, 9))
    put(img, leg["front"], [(1, 4), (2, 4), (1, 5), (2, 5)], PLATE_HI)        # knee
    put(img, leg["front"], [(x, 6) for x in range(4)], STEEL_LO)
    for side in ("right", "left"):                                            # side seam
        put(img, leg[side], [(1, y) for y in range(1, 9)], EMBER[1])
    return img


# ---------------------------------------------------------------------------
# Pack icon
# ---------------------------------------------------------------------------
def pack_icon():
    """Redwoods, a lavender field and a smoking ash cone under a wide sky."""
    s = 32
    img = Image.new("RGB", (s, s))
    for y in range(s):
        for x in range(s):
            img.putpixel((x, y), mixc((120, 176, 228), (214, 230, 240), y / 20) if y < 20 else (0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(17, 20), (23, 9), (25, 9), (31, 20)], fill=(70, 66, 72))       # ash cone
    d.line([(23, 9), (25, 9)], fill=(246, 142, 44))
    d.point([(24, 7), (25, 5), (24, 4)], fill=(200, 200, 204))                 # smoke
    d.rectangle([0, 20, 31, 31], fill=(126, 168, 78))
    for y in range(22, 32):                                                    # lavender rows
        for x in range(0, 32):
            if (x + y * 2) % 5 < 2 and y > 21:
                img.putpixel((x, y), (146, 108, 204) if (x + y) % 2 else (118, 84, 176))
    for x, top in ((4, 4), (10, 7), (15, 10)):                                 # redwoods
        d.line([(x, top + 4), (x, 22)], fill=(124, 62, 44), width=2)
        for i in range(7):
            d.line([(x - 1 - i // 2, top + i), (x + 2 + i // 2, top + i)], fill=(44, 92, 58))
    return img.resize((128, 128), Image.NEAREST)


# ---------------------------------------------------------------------------
# Catalogue
# ---------------------------------------------------------------------------
REDWOOD = [(96, 44, 34), (118, 56, 42), (138, 68, 50), (156, 82, 58)]
CYPRESS = [(88, 78, 66), (104, 92, 78), (120, 108, 92), (136, 124, 106)]
REDWOOD_PLANK = [(150, 74, 54), (166, 86, 62), (180, 98, 72), (192, 110, 82)]
CYPRESS_PLANK = [(186, 162, 110), (198, 176, 124), (210, 188, 138), (220, 200, 152)]


def block_textures():
    rose_ore = crystals(salt(31), 32, ROSE, 7)
    return {
        "redwood_log": bark(1, REDWOOD, (64, 28, 24), 4),
        "redwood_log_top": log_top(2, (104, 48, 36),
                                   [(176, 96, 70), (160, 82, 60), (186, 108, 80), (150, 74, 54)]),
        "redwood_planks": planks(3, REDWOOD_PLANK, (112, 52, 40)),
        "redwood_needles": leaves(4, [(26, 62, 44), (36, 80, 54), (48, 98, 64), (66, 120, 78)], 0.42, (1, 1)),
        "cypress_log": bark(5, CYPRESS, (60, 52, 46), 3),
        "cypress_log_top": log_top(6, (96, 84, 72),
                                   [(214, 194, 146), (198, 176, 126), (224, 206, 160), (190, 168, 118)]),
        "cypress_planks": planks(7, CYPRESS_PLANK, (150, 126, 82)),
        "cypress_leaves": leaves(8, [(30, 70, 60), (40, 88, 70), (52, 106, 80), (74, 130, 96)], 0.3, (0, 1)),
        "volcanic_ash": ash(9),
        "pumice": pumice(10),
        "pumice_bricks": bricks(pumice(11), (122, 118, 112)),
        "salt_block": salt(12),
        "salt_bricks": bricks(salt(13), (176, 190, 206)),
        "rose_salt_ore": rose_ore,
        "emberite_ore": nodules(stone(14), 15, EMBER, 4, rim=(58, 44, 46)),
        "deepslate_emberite_ore": nodules(stone(16, SLATE, layered=True), 17, EMBER, 4, rim=(30, 24, 28)),
        "emberite_block": metal_block(18),
        "ember_lamp": lamp(),
        "lavender": plant_lavender(),
        "ember_shrub": plant_ember_shrub(),
    }


def item_textures():
    return {
        "emberite_sword": icon_sword(), "emberite_pickaxe": icon_pickaxe(),
        "emberite_axe": icon_axe(), "emberite_shovel": icon_shovel(), "emberite_hoe": icon_hoe(),
        "emberite_helmet": icon_helmet(), "emberite_chestplate": icon_chestplate(),
        "emberite_leggings": icon_leggings(), "emberite_boots": icon_boots(),
        "raw_emberite": icon_raw(), "emberite_ingot": icon_ingot(),
        "rose_salt": icon_salt(), "cured_meat": icon_cured(),
    }


def preview(path):
    """Contact sheet of everything, for eyeballing the art."""
    blocks, items = block_textures(), item_textures()
    scale, pad = 8, 6
    cell = 16 * scale + pad
    cols = 7
    tiles = list(blocks.items()) + list(items.items())
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell + pad, rows * cell + pad + 32 * 6 + pad), (58, 92, 70))
    for i, (_, tex) in enumerate(tiles):
        big = tex.resize((16 * scale, 16 * scale), Image.NEAREST)
        sheet.paste(big, (pad + (i % cols) * cell, pad + (i // cols) * cell), big)
    y = rows * cell + pad
    for i, layer in enumerate((armour_layer_1(), armour_layer_2())):
        big = layer.resize((64 * 6, 32 * 6), Image.NEAREST)
        sheet.paste(big, (pad + i * (64 * 6 + pad), y), big)
    icon = pack_icon()
    sheet.paste(icon, (pad + 2 * (64 * 6 + pad), y))
    sheet.save(path)


if __name__ == "__main__":
    import sys
    preview(sys.argv[1])
