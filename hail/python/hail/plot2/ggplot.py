import plotly
import plotly.graph_objects as go

from pprint import pprint
from collections import defaultdict

import hail as hl

from .coord_cartesian import CoordCartesian
from .geoms import Geom, FigureAttribute
from .labels import Labels
from .scale import Scale, ScaleContinuous, ScaleDiscrete, scale_x_continuous, scale_x_genomic, scale_y_continuous, \
    scale_x_discrete, scale_y_discrete, scale_color_discrete, scale_color_continuous
from .aes import Aesthetic, aes


class GGPlot:

    def __init__(self, ht, aes, geoms=[], labels=Labels(), coord_cartesian=None, scales=None,
                 discrete_color_scale=plotly.colors.qualitative.D3, continuous_color_scale=plotly.colors.sequential.Viridis):
        if scales is None:
            scales = {}

        self.ht = ht
        self.aes = aes
        self.geoms = geoms
        self.labels = labels
        self.coord_cartesian = coord_cartesian
        self.scales = scales
        self.discrete_color_scale = discrete_color_scale
        self.discrete_color_dict = {}
        self.discrete_color_idx = 0
        self.continuous_color_scale = continuous_color_scale

        self.add_default_scales(aes)

    def __add__(self, other):
        assert(isinstance(other, FigureAttribute) or isinstance(other, Aesthetic))

        copied = self.copy()
        if isinstance(other, Geom):
            copied.geoms.append(other)
            copied.add_default_scales(other.aes)
        elif isinstance(other, Labels):
            copied.labels = copied.labels.merge(other)
        elif isinstance(other, CoordCartesian):
            copied.coord_cartesian = other
        elif isinstance(other, Scale):
            copied.scales[other.aesthetic_name] = other
        elif isinstance(other, Aesthetic):
            copied.aes = copied.aes.merge(other)
        else:
            raise ValueError("Not implemented")

        return copied

    def add_default_scales(self, aesthetic):
        def is_continuous_type(dtype):
            return dtype in [hl.tint32, hl.tint64, hl.float32, hl.float64]

        def is_genomic_type(dtype):
            return isinstance(dtype, hl.tlocus)

        for aesthetic_str, mapped_expr in aesthetic.items():
            dtype = mapped_expr.dtype
            if aesthetic_str not in self.scales:
                is_continuous = is_continuous_type(dtype)
                # We only know how to come up with a few default scales.
                if aesthetic_str == "x":
                    if is_continuous:
                        self.scales["x"] = scale_x_continuous()
                    elif is_genomic_type(dtype):
                        self.scales["x"] = scale_x_genomic(reference_genome=dtype.reference_genome)
                    else:
                        self.scales["x"] = scale_x_discrete()
                elif aesthetic_str == "y":
                    if is_continuous:
                        self.scales["y"] = scale_y_continuous()
                    elif is_genomic_type(dtype):
                        raise ValueError("Don't yet support y axis genomic")
                    else:
                        self.scales["y"] = scale_y_discrete()
                elif aesthetic_str == "color" and not is_continuous:
                    self.scales["color"] = scale_color_discrete()
                elif aesthetic_str == "color" and is_continuous:
                    self.scales["color"] = scale_color_continuous()
                else:
                    if is_continuous:
                        self.scales[aesthetic_str] = ScaleContinuous(aesthetic_str)
                    else:
                        self.scales[aesthetic_str] = ScaleDiscrete(aesthetic_str)

    def copy(self):
        return GGPlot(self.ht, self.aes, self.geoms[:], self.labels, self.coord_cartesian, self.scales,
                      self.discrete_color_scale, self.continuous_color_scale)

    def render(self):
        fields_to_select = {"figure_mapping": hl.struct(**self.aes)}
        for geom_idx, geom in enumerate(self.geoms):
            label = f"geom{geom_idx}"
            fields_to_select[label] = hl.struct(**geom.aes.properties)

        selected = self.ht.select(**fields_to_select)
        aggregators = {}
        labels_to_stats = {}

        for geom_idx, geom in enumerate(self.geoms):
            label = f"geom{geom_idx}"
            stat = geom.get_stat()

            combined_mapping = selected["figure_mapping"].annotate(**selected[label])

            for key in combined_mapping:
                if key in self.scales:
                    combined_mapping = combined_mapping.annotate(**{key: self.scales[key].transform_data(combined_mapping[key])})

            agg = stat.make_agg(combined_mapping)
            aggregators[label] = agg
            labels_to_stats[label] = stat

        aggregated = selected.aggregate(hl.struct(**aggregators))

        fig = go.Figure()

        for geom, (label, agg_result) in zip(self.geoms, aggregated.items()):
            listified_agg_result = labels_to_stats[label].listify(agg_result)

            if listified_agg_result:
                # apply local scales here
                # grab list of aesthetic names, loop through, grab scale associated with it, called new function with
                # each scale
                relevant_aesthetics =\
                    [scale_name for scale_name in list(listified_agg_result[0]) if scale_name in self.scales]
                for relevant_aesthetic in relevant_aesthetics:
                    listified_agg_result =\
                        self.scales[relevant_aesthetic].transform_data_local(listified_agg_result, self)

                # Ok, need to identify every possible value of every discrete scale.
                discrete_aesthetics = [scale_name for scale_name in relevant_aesthetics if self.scales[scale_name].is_discrete()]
                subsetted_to_discrete = tuple([one_struct.select(*discrete_aesthetics) for one_struct in listified_agg_result])
                group_counter = 0
                def increment_and_return_old():
                    nonlocal group_counter
                    group_counter += 1
                    return group_counter - 1
                numberer = defaultdict(increment_and_return_old)
                numbering = [numberer[data_tuple] for data_tuple in subsetted_to_discrete]

                numbered_result = []
                for s, i in zip(listified_agg_result, numbering):
                    numbered_result.append(s.annotate(group=i))
                listified_agg_result = numbered_result

            geom.apply_to_fig(self, listified_agg_result, fig)

        # Important to update axes after labels, axes names take precedence.
        self.labels.apply_to_fig(fig)
        if self.scales.get("x") is not None:
            self.scales["x"].apply_to_fig(self, fig)
        if self.scales.get("y") is not None:
            self.scales["y"].apply_to_fig(self, fig)
        if self.coord_cartesian is not None:
            self.coord_cartesian.apply_to_fig(fig)

        return fig

    def debug_print(self):
        print("Ggplot Object:")
        print("Aesthetics")
        pprint(self.aes)
        pprint("Scales:")
        pprint(self.scales)
        print("Geoms:")
        pprint(self.geoms)

def ggplot(table, aes=aes()):
    return GGPlot(table, aes)
